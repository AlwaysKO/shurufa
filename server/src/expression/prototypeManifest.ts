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
export const PROTOTYPE_TEXT_PLACEMENTS = ['bottom', 'center'] as const;

export type PrototypeKeyword = typeof PROTOTYPE_KEYWORDS[number];
export type PrototypeSourceType = typeof PROTOTYPE_SOURCE_TYPES[number];
export type PrototypeStyle = typeof PROTOTYPE_STYLES[number];
export type PrototypeMotionPreset = typeof PROTOTYPE_MOTION_PRESETS[number];
export type PrototypeDirection = typeof PROTOTYPE_DIRECTIONS[number];
export type PrototypeTextPlacement = typeof PROTOTYPE_TEXT_PLACEMENTS[number];

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
  poseFiles: string[];
  textPlacement: PrototypeTextPlacement;
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

function optionalString(
  item: Record<string, unknown>,
  field: 'sourceUrl' | 'license',
  index: number,
): string | undefined {
  if (!Object.hasOwn(item, field)) return undefined;
  const value = item[field];
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`第 ${index + 1} 项的 ${field} 声明后必须是非空字符串`);
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
  if (!isRecord(value) || !Array.isArray(value.items)) {
    throw new Error('样板清单必须包含 version 和 items');
  }
  if (typeof value.version !== 'string' || value.version.trim() === '') {
    throw new Error('version 必须是非空字符串');
  }
  if (value.items.length !== 12) {
    throw new Error('样板清单必须恰好 12 项');
  }

  const ids = new Set<string>();
  const masterFiles = new Set<string>();
  const poseFiles = new Set<string>();
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
    const sourceUrl = optionalString(rawItem, 'sourceUrl', index);
    const license = optionalString(rawItem, 'license', index);
    if (sourceType !== 'ai-original') {
      if (sourceUrl === undefined || license === undefined) {
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
    const masterFile = requiredString(rawItem, 'masterFile', index);
    if (!/^masters\/[A-Za-z0-9][A-Za-z0-9._-]*\.png$/.test(masterFile)) {
      throw new Error(`第 ${index + 1} 项的 masterFile 必须是 masters/ 下的受控相对路径且以 .png 结尾`);
    }
    if (masterFiles.has(masterFile)) throw new Error('masterFile 必须唯一');
    masterFiles.add(masterFile);

    if (!Array.isArray(rawItem.poseFiles) || rawItem.poseFiles.length !== 4) {
      throw new Error(`第 ${index + 1} 项的 poseFiles 必须恰好 4 个`);
    }
    const validatedPoseFiles = rawItem.poseFiles.map((poseFile, poseIndex) => {
      if (typeof poseFile !== 'string') {
        throw new Error(`第 ${index + 1} 项的 poseFiles 必须是字符串路径`);
      }
      if (poseFiles.has(poseFile)) throw new Error('poseFiles 必须全清单唯一');
      poseFiles.add(poseFile);
      const expected = `poses/${id}/pose-${String(poseIndex + 1).padStart(2, '0')}.png`;
      const isControlledPath = /^poses\/[A-Za-z0-9][A-Za-z0-9_-]*\/pose-0[1-4]\.png$/
        .test(poseFile);
      if (!isControlledPath || poseFile !== expected) {
        throw new Error('poseFiles 必须依次匹配 poses/<item-id>/pose-01.png 到 pose-04.png');
      }
      return poseFile;
    });

    const textPlacement = requiredString(rawItem, 'textPlacement', index);
    if (!PROTOTYPE_TEXT_PLACEMENTS.includes(textPlacement as PrototypeTextPlacement)) {
      throw new Error('textPlacement 必须来自 bottom|center 白名单');
    }
    if (direction === 'kinetic-type' && textPlacement !== 'center') {
      throw new Error('kinetic-type 的 textPlacement 必须是 center');
    }
    if (direction !== 'kinetic-type' && textPlacement !== 'bottom') {
      throw new Error('非 kinetic-type 的 textPlacement 必须是 bottom');
    }

    return {
      id,
      keyword: typedKeyword,
      text,
      style: style as PrototypeStyle,
      direction: direction as PrototypeDirection,
      sourceType: sourceType as PrototypeSourceType,
      ...(sourceUrl === undefined ? {} : { sourceUrl }),
      ...(license === undefined ? {} : { license }),
      prompt,
      motionPreset: motionPreset as PrototypeMotionPreset,
      frameCount: rawItem.frameCount,
      durationMs: rawItem.durationMs,
      masterFile,
      poseFiles: validatedPoseFiles,
      textPlacement: textPlacement as PrototypeTextPlacement,
    };
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

  return {
    version: value.version,
    items: normalizedItems,
  };
}
