import type pg from 'pg';
import { pinyin } from 'pinyin-pro';

/** 计入高频统计的事件类型 */
const TEXT_EVENT_TYPES = ['commit', 'candidate_commit', 'paste', 'paste_inferred', 'external_insert', 'voice'];

/** 从一段文本中提取候选词片段（中文连续片段，2~6 字） */
function extractWords(text: string): string[] {
  const segments = text.match(/[\u4e00-\u9fff]+/g) ?? [];
  const words: string[] = [];
  for (const seg of segments) {
    if (seg.length >= 2 && seg.length <= 6) {
      words.push(seg);
    } else if (seg.length > 6) {
      // 长句按 2~4 字滑动窗口取词
      for (let len = 2; len <= 4; len++) {
        for (let i = 0; i + len <= seg.length; i++) {
          words.push(seg.slice(i, i + len));
        }
      }
    }
  }
  return words;
}

/**
 * 短语/词频分析（增量）：
 * 统计 analysis_state['last_analyzed_at'] 之后的新事件，UPSERT 到 phrase_stat。
 */
export async function analyzePhrases(pool: pg.Pool, now: Date, userId: string): Promise<{ phrases: number; words: number }> {
  const cursorKey = `last_analyzed_epoch_ms:${userId}`;
  // 获取上次分析游标
  const cursorRes = await pool.query(
    `INSERT INTO analysis_state (key, value) VALUES ($1, 0)
     ON CONFLICT (key) DO UPDATE SET value = analysis_state.value
     RETURNING value`,
    [cursorKey],
  );
  const lastCursor = Number(cursorRes.rows[0].value);

  // 用 created_at 时间戳做增量游标（避免 UUID 无法比较）
  const cursorTs = new Date(lastCursor);
  const events = await pool.query(
    `SELECT text, package_name, occurred_at
     FROM input_event
     WHERE user_id = $1
       AND event_type = ANY($2::text[])
       AND text IS NOT NULL
       AND length(text) BETWEEN 1 AND 200
       AND created_at > $3
     ORDER BY created_at ASC`,
    [userId, TEXT_EVENT_TYPES, cursorTs],
  );

  const phraseCounts = new Map<string, { count: number; pkg: string | null; lastUsed: string }>();
  const wordCounts = new Map<string, { count: number; pkg: string | null; lastUsed: string }>();

  for (const row of events.rows as Array<{ text: string; package_name: string | null; occurred_at: string }>) {
    const text = row.text.trim();
    if (!text) continue;

    const key = `${text}\u0000${row.package_name ?? ''}`;
    const cur = phraseCounts.get(key) ?? { count: 0, pkg: row.package_name, lastUsed: row.occurred_at };
    cur.count++;
    if (row.occurred_at > cur.lastUsed) cur.lastUsed = row.occurred_at;
    phraseCounts.set(key, cur);

    for (const w of extractWords(text)) {
      const wk = `${w}\u0000${row.package_name ?? ''}`;
      const wc = wordCounts.get(wk) ?? { count: 0, pkg: row.package_name, lastUsed: row.occurred_at };
      wc.count++;
      if (row.occurred_at > wc.lastUsed) wc.lastUsed = row.occurred_at;
      wordCounts.set(wk, wc);
    }
  }

  // UPSERT 短语统计
  let phrases = 0;
  for (const [key, v] of phraseCounts) {
    const [phrase, pkg] = key.split('\u0000');
    const r = await pool.query(
      `INSERT INTO phrase_stat (user_id, phrase, package_name, use_count, use_days, last_used_at, score)
       VALUES ($1,$2,$3,$4,1,$5,0)
       ON CONFLICT (user_id, phrase, package_name) DO UPDATE SET
         use_count = phrase_stat.use_count + EXCLUDED.use_count,
         last_used_at = GREATEST(phrase_stat.last_used_at, EXCLUDED.last_used_at),
         use_days = (SELECT COUNT(DISTINCT date(occurred_at)) FROM input_event
                     WHERE user_id = $1 AND text = $2 AND event_type = ANY($6::text[]))
       RETURNING id`,
      [userId, phrase, pkg ?? null, v.count, v.lastUsed, TEXT_EVENT_TYPES],
    );
    phrases += r.rowCount ?? 0;
  }

  // UPSERT 词统计（词也放 phrase_stat，用长度 <= 6 区分；短语一般是完整句子）
  let words = 0;
  for (const [key, v] of wordCounts) {
    const [word, pkg] = key.split('\u0000');
    const r = await pool.query(
      `INSERT INTO phrase_stat (user_id, phrase, package_name, use_count, use_days, last_used_at, score)
       VALUES ($1,$2,$3,$4,1,$5,0)
       ON CONFLICT (user_id, phrase, package_name) DO UPDATE SET
         use_count = phrase_stat.use_count + EXCLUDED.use_count,
         last_used_at = GREATEST(phrase_stat.last_used_at, EXCLUDED.last_used_at)
       RETURNING id`,
      [userId, word, pkg ?? null, v.count, v.lastUsed],
    );
    words += r.rowCount ?? 0;
  }

  // 更新游标
  await pool.query(
    `INSERT INTO analysis_state (key, value) VALUES ($1, $2)
     ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()`,
    [cursorKey, now.getTime()],
  );

  return { phrases, words };
}

/** 补全候选生成：从高频短语生成 prefix → completion 映射，评分并写入 completion_candidate */
export async function generateCompletions(pool: pg.Pool, userId: string): Promise<number> {
  // 至少使用 3 次才认为是习惯（对话设计：use_count < 3 不生成候选）
  const phrases = await pool.query(
    `SELECT phrase, package_name, use_count, last_used_at
     FROM phrase_stat
     WHERE user_id = $1 AND use_count >= 3 AND length(phrase) >= 2
     ORDER BY use_count DESC
     LIMIT 5000`,
    [userId],
  );

  const rows = phrases.rows as Array<{
    phrase: string;
    package_name: string | null;
    use_count: number;
    last_used_at: string | null;
  }>;

  // 全量重算：清空旧候选，version + 1
  const versionRes = await pool.query(
    `SELECT COALESCE(MAX(version), 0) + 1 AS v FROM completion_candidate WHERE user_id = $1`,
    [userId],
  );
  const version = Number(versionRes.rows[0].v);
  await pool.query('DELETE FROM completion_candidate WHERE user_id = $1', [userId]);

  const days = (ts: string | null): number => {
    if (!ts) return 0;
    return Math.max(0, Math.floor((Date.now() - new Date(ts).getTime()) / 86_400_000));
  };

  let inserted = 0;
  for (const row of rows) {
    const { phrase, package_name, use_count, last_used_at } = row;
    const recencyDays = days(last_used_at);
    // 评分：频率 0.5 + 新鲜度 0.2 + 全局归一化；个人单 App 场景 app 维度暂时并入频率
    const frequencyScore = Math.min(1, use_count / 50);
    const recencyScore = Math.max(0, 1 - recencyDays / 30);
    const score = frequencyScore * 0.8 + recencyScore * 0.2;

    // 为每个前缀生成候选：取短语前 1..n-1 字作为 prefix（最多 6 个前缀）
    const chars = [...phrase];
    const maxPrefix = Math.min(chars.length - 1, 6);
    const prefixes = new Set<string>();
    for (let i = 1; i <= maxPrefix; i++) {
      prefixes.add(chars.slice(0, i).join(''));
    }
    for (const prefix of prefixes) {
      if (!prefix) continue;
      // 前缀对应的拼音（全拼 + 首字母），供拼音阶段匹配
      const prefixFull = pinyin(prefix, { toneType: 'none', type: 'array', nonZh: 'consecutive' }).join('');
      const prefixInitials = pinyin(prefix, { pattern: 'first', toneType: 'none', type: 'array', nonZh: 'consecutive' }).join('');
      const pRes = await pool.query(
        `INSERT INTO completion_candidate
           (user_id, prefix, prefix_pinyin, prefix_initials, completion, package_name,
            use_count, score, last_used_at, version)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
         ON CONFLICT (user_id, prefix, completion, package_name) DO UPDATE SET
           use_count = EXCLUDED.use_count,
           score = EXCLUDED.score,
           last_used_at = EXCLUDED.last_used_at,
           version = EXCLUDED.version
         RETURNING id`,
        [userId, prefix, prefixFull, prefixInitials, phrase, package_name, use_count, score, last_used_at, version],
      );
      inserted += pRes.rowCount ?? 0;
    }
  }

  return inserted;
}

/** 完整分析流程（job 入口） */
export async function runAnalysis(pool: pg.Pool): Promise<{ phrases: number; words: number; completions: number }> {
  const now = new Date();
  const users = await pool.query<{ user_id: string }>(
    `SELECT DISTINCT user_id FROM input_event
     UNION SELECT DISTINCT user_id FROM phrase_stat`,
  );
  const total = { phrases: 0, words: 0, completions: 0 };
  for (const { user_id: userId } of users.rows) {
    const phraseRes = await analyzePhrases(pool, now, userId);
    const completions = await generateCompletions(pool, userId);
    total.phrases += phraseRes.phrases;
    total.words += phraseRes.words;
    total.completions += completions;
  }
  return total;
}
