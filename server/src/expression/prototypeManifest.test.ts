import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  type PrototypeManifest,
  validatePrototypeManifest,
} from './prototypeManifest.js';

const prompt = '完全原创的表情主视觉，无文字，无水印，无品牌，无现有角色，主体居中并预留动作空间';
const styles = [
  'original-character',
  'ai-original-pet',
  'original-life-scene',
  '3d-plush',
  'hand-drawn',
  'fictional-live-action',
  'kinetic-typography',
  'internet-meme-grammar',
] as const;
const motions = ['bow', 'shake', 'laugh', 'impact'] as const;

function validManifest(): PrototypeManifest {
  const keywords = ['谢谢', '无语', '笑死'] as const;
  return {
    version: '2026.09.03.1',
    items: keywords.flatMap((keyword, keywordIndex) => Array.from({ length: 4 }, (_, index) => ({
      id: `prototype-${keywordIndex + 1}-${index + 1}`,
      keyword,
      text: keyword,
      style: styles[(keywordIndex * 4 + index) % styles.length],
      sourceType: 'ai-original' as const,
      prompt,
      motionPreset: motions[(keywordIndex * 4 + index) % motions.length],
      frameCount: 10 + index * 2,
      durationMs: 900 + index * 200,
      masterFile: `masters/prototype-${keywordIndex + 1}-${index + 1}.png`,
    }))),
  };
}

function cloneManifest(): PrototypeManifest {
  return structuredClone(validManifest());
}

describe('validatePrototypeManifest', () => {
  it('接受符合全部约束的十二项样板清单', () => {
    expect(validatePrototypeManifest(validManifest())).toEqual(validManifest());
  });

  it('拒绝数量或关键词分组不正确的清单', () => {
    const wrongCount = cloneManifest();
    wrongCount.items.pop();
    expect(() => validatePrototypeManifest(wrongCount)).toThrow(/恰好 12 项/);

    const wrongGroup = cloneManifest();
    wrongGroup.items[0].keyword = '无语';
    wrongGroup.items[0].text = '无语';
    expect(() => validatePrototypeManifest(wrongGroup)).toThrow(/每个关键词必须恰好 4 项/);
  });

  it('拒绝重复 ID', () => {
    const manifest = cloneManifest();
    manifest.items[1].id = manifest.items[0].id;
    expect(() => validatePrototypeManifest(manifest)).toThrow(/ID.*唯一/);
  });

  it('拒绝非法来源类型', () => {
    const manifest = cloneManifest();
    manifest.items[0].sourceType = 'unverified-web' as never;
    expect(() => validatePrototypeManifest(manifest)).toThrow(/来源类型/);
  });

  it('拒绝未记录地址和许可证的外部来源', () => {
    const manifest = cloneManifest();
    manifest.items[0].sourceType = 'cc0';
    expect(() => validatePrototypeManifest(manifest)).toThrow(/sourceUrl.*license/);
  });

  it('接受记录完整的授权外部来源', () => {
    const manifest = cloneManifest();
    manifest.items[0].sourceType = 'public-domain';
    manifest.items[0].sourceUrl = 'https://example.test/public-domain/source.png';
    manifest.items[0].license = 'Public Domain Mark 1.0';
    expect(validatePrototypeManifest(manifest)).toEqual(manifest);
  });

  it('拒绝空 prompt', () => {
    const manifest = cloneManifest();
    manifest.items[0].prompt = '   ';
    expect(() => validatePrototypeManifest(manifest)).toThrow(/prompt/);
  });

  it.each(['原创', '无文字', '无水印', '无品牌', '无现有角色']) (
    '拒绝缺少“%s”安全约束的 prompt',
    (constraint) => {
      const manifest = cloneManifest();
      manifest.items[0].prompt = manifest.items[0].prompt.replace(constraint, '');
      expect(() => validatePrototypeManifest(manifest)).toThrow(/prompt.*安全约束/);
    },
  );

  it('拒绝白名单之外的动作预设', () => {
    const manifest = cloneManifest();
    manifest.items[0].motionPreset = 'spin-forever' as never;
    expect(() => validatePrototypeManifest(manifest)).toThrow(/动作预设/);
  });

  it('拒绝帧数或时长越界', () => {
    const tooFewFrames = cloneManifest();
    tooFewFrames.items[0].frameCount = 9;
    expect(() => validatePrototypeManifest(tooFewFrames)).toThrow(/frameCount/);

    const tooManyFrames = cloneManifest();
    tooManyFrames.items[0].frameCount = 21;
    expect(() => validatePrototypeManifest(tooManyFrames)).toThrow(/frameCount/);

    const tooShort = cloneManifest();
    tooShort.items[0].durationMs = 799;
    expect(() => validatePrototypeManifest(tooShort)).toThrow(/durationMs/);

    const tooLong = cloneManifest();
    tooLong.items[0].durationMs = 2001;
    expect(() => validatePrototypeManifest(tooLong)).toThrow(/durationMs/);
  });

  it('拒绝与关键词不同的表情文字', () => {
    const manifest = cloneManifest();
    manifest.items[0].text = '多谢';
    expect(() => validatePrototypeManifest(manifest)).toThrow(/text.*keyword/);
  });

  it('拒绝少于六类视觉风格的清单', () => {
    const manifest = cloneManifest();
    manifest.items.forEach((item, index) => {
      item.style = styles[index % 5];
    });
    expect(() => validatePrototypeManifest(manifest)).toThrow(/至少覆盖 6 类/);
  });

  it('拒绝同一关键词的四项同时使用相同风格和动作', () => {
    const manifest = cloneManifest();
    manifest.items.slice(0, 4).forEach((item) => {
      item.style = 'original-character';
      item.motionPreset = 'bow';
    });
    expect(() => validatePrototypeManifest(manifest)).toThrow(/风格或动作不能全相同/);
  });

  it('真实样板清单通过校验并包含三个目标关键词', () => {
    const path = fileURLToPath(new URL('../../../assets/expression/prototypes/manifest.json', import.meta.url));
    const manifest = JSON.parse(readFileSync(path, 'utf8')) as unknown;
    const validated = validatePrototypeManifest(manifest);

    expect(validated.items).toHaveLength(12);
    expect(new Set(validated.items.map(({ keyword }) => keyword)))
      .toEqual(new Set(['谢谢', '无语', '笑死']));
  });
});
