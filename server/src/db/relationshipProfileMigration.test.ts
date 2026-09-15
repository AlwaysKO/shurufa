import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { beforeEach, describe, expect, it } from 'vitest';

const chatMigrationPath = fileURLToPath(
  new URL('../../migrations/007_chat_capture.sql', import.meta.url),
);
const relationshipMigrationPath = fileURLToPath(
  new URL('../../migrations/008_relationship_profile.sql', import.meta.url),
);

let pool: pg.Pool;
let userId: string;
let conversationId: number;

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  await pool.query(readFileSync(chatMigrationPath, 'utf8'));
  await pool.query(readFileSync(relationshipMigrationPath, 'utf8'));
  userId = crypto.randomUUID();
  const conversation = await pool.query<{ id: number }>(`INSERT INTO chat_conversation
    (user_id, platform, account_key, external_key, conversation_type, identity_confidence)
    VALUES ($1, 'wechat', 'account', 'peer', 'direct', 0.95)
    RETURNING id`, [userId]);
  conversationId = Number(conversation.rows[0].id);
});

describe('008_relationship_profile.sql', () => {
  it('同一用户和会话只能有一个关系档案', async () => {
    const insert = `INSERT INTO relationship_profile
      (user_id, conversation_id, relationship_type, intimacy_level, humor_level)
      VALUES ($1, $2, 'friend', 80, 70)`;

    await pool.query(insert, [userId, conversationId]);
    await expect(pool.query(insert, [userId, conversationId])).rejects.toThrow();
  });

  it('拒绝未知关系类型和超出范围的等级', async () => {
    const insert = `INSERT INTO relationship_profile
      (user_id, conversation_id, relationship_type, intimacy_level, humor_level)
      VALUES ($1, $2, $3, $4, $5)`;

    await expect(pool.query(insert, [userId, conversationId, 'stranger', 50, 50]))
      .rejects.toThrow();
    await expect(pool.query(insert, [userId, conversationId, 'friend', 101, 50]))
      .rejects.toThrow();
    await expect(pool.query(insert, [userId, conversationId, 'friend', 50, -1]))
      .rejects.toThrow();
  });

  it('删除会话时级联删除关系档案', async () => {
    await pool.query(`INSERT INTO relationship_profile
      (user_id, conversation_id, relationship_type)
      VALUES ($1, $2, 'family')`, [userId, conversationId]);

    await pool.query('DELETE FROM chat_conversation WHERE id = $1', [conversationId]);

    const result = await pool.query('SELECT id FROM relationship_profile');
    expect(result.rows).toHaveLength(0);
  });
});
