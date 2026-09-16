import type pg from 'pg';

const EDIT_TYPES = ['commit', 'candidate_commit', 'delete', 'external_insert', 'external_delete', 'paste', 'paste_inferred', 'voice'];
const EDIT_TYPES_SQL = EDIT_TYPES.map(type => `'${type}'`).join(',');
interface EditEvent {
  id: string;
  user_id?: string;
  device_id: string;
  package_name: string | null;
  editor_id: string | null;
  session_id: string | null;
  sequence_no: number | string | null;
  occurred_at: string | Date;
  event_type: string;
  text?: string | null;
  client_ip?: string | null;
  ip_location?: string | null;
  text_before: string | null;
  text_after: string | null;
  metadata?: { edit_protocol?: unknown; snapshot_complete?: unknown };
  [key: string]: unknown;
}

function isProtocolEdit(row: EditEvent): boolean {
  return row.metadata?.edit_protocol === 1 && !!row.session_id && !!row.package_name && !!row.editor_id
    && EDIT_TYPES.includes(row.event_type);
}

/** Completeness means the available editing chain, never proof the message was sent. */
export function summarizeEditGroup(input: EditEvent[]) {
  if (!input.length) throw new Error('Empty edit group');
  const events = [...input].sort((a, b) => {
    const seq = Number(a.sequence_no) - Number(b.sequence_no);
    return seq || new Date(a.occurred_at).getTime() - new Date(b.occurred_at).getTime() || a.id.localeCompare(b.id);
  });
  const first = events[0];
  const complete = events.every((event, i) => isProtocolEdit(event)
    && ['user_id', 'device_id', 'package_name', 'editor_id', 'session_id'].every(key => event[key] === first[key])
    && Number.isSafeInteger(Number(event.sequence_no)) && Number(event.sequence_no) === i + 1
    && event.metadata?.snapshot_complete === true
    && typeof event.text_before === 'string' && typeof event.text_after === 'string'
    && (i === 0 || events[i - 1].text_after === event.text_before));
  return { ...events[events.length - 1], edit_count: events.length, edit_complete: complete, edit_events: events };
}

/** Group identity is structured JSON, avoiding delimiter collisions and cross-editor merges. */
const GROUP_KEY = `CASE WHEN metadata->'edit_protocol' = '1'::jsonb
  AND session_id IS NOT NULL AND COALESCE(package_name, '') <> '' AND COALESCE(editor_id, '') <> ''
  AND event_type IN (${EDIT_TYPES_SQL})
  THEN jsonb_build_array('edit', user_id, device_id, package_name, editor_id, session_id)
  ELSE jsonb_build_array('raw', id) END`;

export async function queryGroupedEdits(pool: pg.Pool, where: string, params: unknown[], contentTypeSql: string, page: number, pageSize: number) {
  // PostgreSQL does grouping/count/paging. Only selected groups' complete raw members reach Node.
  // Filters choose keys, NOT fragments: a deleted-word/date/type match keeps the entire history.
  const result = await pool.query(`WITH scoped AS NOT MATERIALIZED (
      SELECT input_event.*, ${GROUP_KEY} AS edit_key, ${contentTypeSql} AS content_type
      FROM input_event WHERE user_id = $1
    ), matched AS (
      SELECT DISTINCT edit_key FROM scoped WHERE ${where}
    ), ordered AS (
      SELECT scoped.edit_key, MAX(occurred_at) AS latest
      FROM scoped JOIN matched USING (edit_key) GROUP BY scoped.edit_key
    ), selected AS (
      SELECT * FROM ordered ORDER BY latest DESC, edit_key
      LIMIT $${params.length + 1} OFFSET $${params.length + 2}
    ), histories AS (
      SELECT selected.edit_key, selected.latest,
        jsonb_agg(to_jsonb(scoped) - 'edit_key') AS events
      FROM selected JOIN scoped USING (edit_key)
      GROUP BY selected.edit_key, selected.latest
    ) SELECT (SELECT COUNT(*)::int FROM matched) AS total,
      COALESCE((SELECT jsonb_agg(events ORDER BY latest DESC, edit_key) FROM histories), '[]'::jsonb) AS histories`,
  [...params, pageSize, (page - 1) * pageSize]);
  const row = result.rows[0] as { total: number; histories: EditEvent[][] };
  return { total: row.total, items: row.histories.map(summarizeEditGroup) };
}
