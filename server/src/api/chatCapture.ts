import { Router } from 'express';
import type pg from 'pg';
import { validateCapturedConversation, validateCapturedMessage } from '../domain/chatValidation.js';
import { AssetValidationError, storeAsset, type StoreAssetInput } from '../chat/assetStorage.js';
import { ingestCapturedMessages } from '../chat/chatRepository.js';

const DEFAULT_USER_ID = process.env.DEFAULT_USER_ID ?? '00000000-0000-0000-0000-000000000001';
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function validationMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'invalid request';
}

export function createMobileChatCaptureRouter(pool: pg.Pool): Router {
  const router = Router();

  router.post('/assets', async (req, res, next) => {
    try {
      const result = await storeAsset(pool, DEFAULT_USER_ID, req.body as StoreAssetInput);
      res.json({ ok: true, ...result });
    } catch (error) {
      if (error instanceof AssetValidationError) {
        return res.status(400).json({ error: error.message });
      }
      next(error);
    }
  });

  router.post('/messages/batch', async (req, res, next) => {
    try {
      const body = req.body as {
        device_id?: unknown;
        conversation?: unknown;
        messages?: unknown;
      };
      if (typeof body?.device_id !== 'string' || !UUID_PATTERN.test(body.device_id)) {
        return res.status(400).json({ error: 'device_id is invalid' });
      }
      if (!Array.isArray(body.messages)) {
        return res.status(400).json({ error: 'messages must be an array' });
      }
      if (body.messages.length > 200) {
        return res.status(400).json({ error: 'batch too large (max 200)' });
      }

      let conversation;
      let messages;
      try {
        conversation = validateCapturedConversation(body.conversation);
        messages = body.messages.map(validateCapturedMessage);
      } catch (error) {
        return res.status(400).json({ error: validationMessage(error) });
      }

      const result = await ingestCapturedMessages(
        pool,
        DEFAULT_USER_ID,
        body.device_id,
        conversation,
        messages,
      );
      res.json({ ok: true, ...result });
    } catch (error) {
      next(error);
    }
  });

  return router;
}
