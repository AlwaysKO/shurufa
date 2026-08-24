import { Router } from 'express';
import type pg from 'pg';
import { validateRelationshipCandidateQuery } from '../domain/relationshipValidation.js';
import { getZeroTokenCandidates } from '../relationship/zeroTokenCandidates.js';

const DEFAULT_USER_ID = process.env.DEFAULT_USER_ID ?? '00000000-0000-0000-0000-000000000001';

function validationMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'invalid request';
}

export function createMobileRelationshipsRouter(pool: pg.Pool): Router {
  const router = Router();

  router.post('/candidates', async (req, res, next) => {
    try {
      let query;
      try {
        query = validateRelationshipCandidateQuery(req.body);
      } catch (error) {
        return res.status(400).json({ error: validationMessage(error) });
      }
      const result = await getZeroTokenCandidates(
        pool,
        DEFAULT_USER_ID,
        query.identity,
        query.contextText,
        query.limit,
      );
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  return router;
}
