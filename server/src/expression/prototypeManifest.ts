export const PROTOTYPE_KEYWORDS = ['谢谢', '无语', '笑死'] as const;
export const PROTOTYPE_SOURCE_TYPES = [
  'ai-original',
  'cc0',
  'public-domain',
  'licensed',
] as const;
export const PROTOTYPE_STYLES = [
  'original-character',
  'ai-original-pet',
  'original-life-scene',
  '3d-plush',
  'hand-drawn',
  'fictional-live-action',
  'kinetic-typography',
  'internet-meme-grammar',
] as const;
export const PROTOTYPE_MOTION_PRESETS = ['bow', 'shake', 'laugh', 'impact'] as const;
export const PROTOTYPE_DIRECTIONS = [
  'core-performance',
  'pet-or-person',
  'kinetic-type',
  'contrast-remix',
] as const;

export type PrototypeKeyword = typeof PROTOTYPE_KEYWORDS[number];
export type PrototypeSourceType = typeof PROTOTYPE_SOURCE_TYPES[number];
export type PrototypeStyle = typeof PROTOTYPE_STYLES[number];
export type PrototypeMotionPreset = typeof PROTOTYPE_MOTION_PRESETS[number];
export type PrototypeDirection = typeof PROTOTYPE_DIRECTIONS[number];

export interface PrototypeManifestItem {
  id: string;
  keyword: PrototypeKeyword;
  text: string;
  style: PrototypeStyle;
  direction: PrototypeDirection;
  sourceType: PrototypeSourceType;
  sourceUrl?: string;
  license?: string;
  prompt: string;
  motionPreset: PrototypeMotionPreset;
  frameCount: number;
  durationMs: number;
  masterFile: string;
}

export interface PrototypeManifest {
  version: string;
  items: PrototypeManifestItem[];
}

const REQUIRED_PROMPT_CONSTRAINTS = ['原创', '无文字', '无水印', '无品牌', '无现有角色'];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requiredString(item: Record<string, unknown>, field: string, index: number): string {
  const value = item[field];
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`第 ${index + 1} 项的 ${field} 必须是非空字符串`);
  }
  return value;
}

function assertIntegerInRange(
  value: unknown,
  field: string,
  minimum: number,
  maximum: number,
  index: number,
): asserts value is number {
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw new Error(`第 ${index + 1} 项的 ${field} 必须在 ${minimum}–${maximum} 之间`);
  }
}

/** 校验评审区样板清单；失败时抛出包含具体字段的错误。 */
export function validatePrototypeManifest(value: unknown): PrototypeManifest {
  if (!isRecord(value) || typeof value.version !== 'string' || !Array.isArray(value.items)) {
    throw new Error('样板清单必须包含 version 和 items');
  }
  if (value.items.length !== 12) {
    throw new Error('样板清单必须恰好 12 项');
  }

  const ids = new Set<string>();
  const keywordCounts = new Map<PrototypeKeyword, number>(
    PROTOTYPE_KEYWORDS.map((keyword) => [keyword, 0]),
  );
  const normalizedItems = value.items.map((rawItem, index) => {
    if (!isRecord(rawItem)) throw new Error(`第 ${index + 1} 项必须是对象`);

    const id = requiredString(rawItem, 'id', index);
    if (ids.has(id)) throw new Error('样板 ID 必须唯一');
    ids.add(id);

    const keyword = requiredString(rawItem, 'keyword', index);
    if (!PROTOTYPE_KEYWORDS.includes(keyword as PrototypeKeyword)) {
      throw new Error(`不支持的关键词：${keyword}`);
    }
    const typedKeyword = keyword as PrototypeKeyword;
    keywordCounts.set(typedKeyword, (keywordCounts.get(typedKeyword) ?? 0) + 1);

    const text = requiredString(rawItem, 'text', index);
    if (text !== keyword) throw new Error('每项 text 必须等于 keyword');

    const style = requiredString(rawItem, 'style', index);
    if (!PROTOTYPE_STYLES.includes(style as PrototypeStyle)) {
      throw new Error(`第 ${index + 1} 项使用了未知视觉风格`);
    }

    const direction = requiredString(rawItem, 'direction', index);
    if (!PROTOTYPE_DIRECTIONS.includes(direction as PrototypeDirection)) {
      throw new Error(`第 ${index + 1} 项使用了非法内容方向`);
    }

    const sourceType = requiredString(rawItem, 'sourceType', index);
    if (!PROTOTYPE_SOURCE_TYPES.includes(sourceType as PrototypeSourceType)) {
      throw new Error(`第 ${index + 1} 项使用了非法来源类型`);
    }
    if (sourceType !== 'ai-original') {
      const sourceUrl = rawItem.sourceUrl;
      const license = rawItem.license;
      if (typeof sourceUrl !== 'string' || sourceUrl.trim() === ''
        || typeof license !== 'string' || license.trim() === '') {
        throw new Error('非 ai-original 来源必须同时记录 sourceUrl 和 license');
      }
    }

    const prompt = requiredString(rawItem, 'prompt', index);
    if (!REQUIRED_PROMPT_CONSTRAINTS.every((constraint) => prompt.includes(constraint))) {
      throw new Error(`第 ${index + 1} 项的 prompt 缺少安全约束`);
    }

    const motionPreset = requiredString(rawItem, 'motionPreset', index);
    if (!PROTOTYPE_MOTION_PRESETS.includes(motionPreset as PrototypeMotionPreset)) {
      throw new Error(`第 ${index + 1} 项使用了非法动作预设`);
    }

    assertIntegerInRange(rawItem.frameCount, 'frameCount', 10, 20, index);
    assertIntegerInRange(rawItem.durationMs, 'durationMs', 800, 2_000, index);
    requiredString(rawItem, 'masterFile', index);

    return rawItem as unknown as PrototypeManifestItem;
  });

  if (Array.from(keywordCounts.values()).some((count) => count !== 4)) {
    throw new Error('谢谢、无语、笑死每个关键词必须恰好 4 项');
  }
  if (new Set(normalizedItems.map(({ style }) => style)).size < 6) {
    throw new Error('样板清单的视觉风格必须至少覆盖 6 类');
  }
  for (const keyword of PROTOTYPE_KEYWORDS) {
    const group = normalizedItems.filter((item) => item.keyword === keyword);
    if (new Set(group.map(({ direction }) => direction)).size !== PROTOTYPE_DIRECTIONS.length) {
      throw new Error(`${keyword}必须覆盖四种内容方向各一项`);
    }
    const oneStyle = new Set(group.map(({ style }) => style)).size === 1;
    const oneMotion = new Set(group.map(({ motionPreset }) => motionPreset)).size === 1;
    if (oneStyle && oneMotion) {
      throw new Error(`${keyword}的四项在风格或动作不能全相同`);
    }
  }

  return value as unknown as PrototypeManifest;
}
