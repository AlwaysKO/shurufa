import { mkdir, rm, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { cropExpressionContactSheet } from '../src/expression/assetGenerator.js';
import { EXPRESSION_CATALOG_VERSION } from '../src/expression/catalogVersion.js';

const projectRoot = resolve(import.meta.dirname, '..', '..');
const sourceRoot = resolve(projectRoot, 'assets', 'expression');
const contactSheetsRoot = resolve(sourceRoot, 'contact-sheets');
const templatesRoot = resolve(sourceRoot, 'templates');
const emojiBaseRoot = resolve(sourceRoot, 'emoji-base');

const templateDefinitions = [
  ['开心庆祝', 'happy'], ['感谢夸奖', 'happy'], ['加油打气', 'encourage'], ['胜利成功', 'proud'],
  ['幸运惊喜', 'surprised'], ['同意点赞', 'approve'], ['开心跳舞', 'happy'], ['暖心感谢', 'grateful'],
  ['默契击掌', 'happy'], ['派对庆祝', 'happy'], ['骄傲成就', 'proud'], ['期待兴奋', 'excited'],
  ['鼓励支持', 'encourage'], ['乐观阳光', 'happy'], ['鼓掌喝彩', 'praise'], ['大笑开心', 'happy'],
  ['尴尬无语', 'speechless'], ['怀疑斜眼', 'skeptical'], ['调侃嘲讽', 'tease'], ['捂脸无奈', 'speechless'],
  ['疑惑不解', 'confused'], ['疲惫叹气', 'tired'], ['社死尴尬', 'embarrassed'], ['嫌弃冷漠', 'annoyed'],
  ['坏笑调皮', 'mischievous'], ['阴阳鼓掌', 'tease'], ['离谱震惊', 'surprised'], ['无奈摊手', 'speechless'],
  ['发呆走神', 'bored'], ['尴尬难受', 'embarrassed'], ['偷笑憋笑', 'mischievous'], ['白眼嫌弃', 'annoyed'],
  ['拒绝不要', 'refuse'], ['生气抗议', 'angry'], ['愤怒爆发', 'furious'], ['警告注意', 'warning'],
  ['停止别动', 'refuse'], ['催促快点', 'urgent'], ['截止时间', 'urgent'], ['慌张着急', 'panic'],
  ['震惊吓到', 'surprised'], ['伤心哭泣', 'sad'], ['道歉认错', 'sorry'], ['愧疚后悔', 'sorry'],
  ['失望孤单', 'disappointed'], ['累瘫没电', 'exhausted'], ['害怕发抖', 'afraid'], ['坚定反击', 'determined'],
  ['喜欢爱心', 'love'], ['撒娇请求', 'pleading'], ['调皮眨眼', 'mischievous'], ['害羞告白', 'shy'],
  ['耐心等待', 'waiting'], ['等不及了', 'urgent'], ['晚安睡觉', 'sleepy'], ['早安活力', 'happy'],
  ['想吃美食', 'hungry'], ['拥抱安慰', 'love'], ['挥手再见', 'goodbye'], ['欢迎你好', 'hello'],
] as const;

const recommendationAliases: Record<string, readonly string[]> = {
  'tpl-01': ['开心'],
  'tpl-02': ['谢谢'],
  'tpl-03': ['加油'],
  'tpl-04': ['加油'],
  'tpl-06': ['好的', '可以'],
  'tpl-07': ['开心'],
  'tpl-08': ['谢谢'],
  'tpl-09': ['好的', '可以'],
  'tpl-10': ['开心'],
  'tpl-12': ['你好'],
  'tpl-13': ['加油'],
  'tpl-14': ['你好', '早安'],
  'tpl-15': ['谢谢'],
  'tpl-16': ['开心', '哈哈'],
  'tpl-17': ['无语'],
  'tpl-20': ['无语'],
  'tpl-28': ['无语'],
  'tpl-31': ['哈哈'],
  'tpl-33': ['不要'],
  'tpl-34': ['生气'],
  'tpl-35': ['生气'],
  'tpl-37': ['不要'],
  'tpl-38': ['快点'],
  'tpl-39': ['快点'],
  'tpl-43': ['抱歉', '对不起'],
  'tpl-44': ['抱歉', '对不起'],
  'tpl-48': ['加油'],
  'tpl-49': ['喜欢', '爱你'],
  'tpl-52': ['喜欢', '爱你'],
  'tpl-53': ['晚安', '再见'],
  'tpl-54': ['快点'],
  'tpl-55': ['晚安'],
  'tpl-56': ['早安'],
  'tpl-58': ['谢谢', '喜欢', '爱你'],
  'tpl-59': ['你好', '再见'],
  'tpl-60': ['你好'],
};

const prebuiltPhrases = [
  { text: '你好', aliases: ['您好'], templateIds: ['tpl-12', 'tpl-14', 'tpl-59', 'tpl-60'] },
  { text: '谢谢', aliases: ['多谢'], templateIds: ['tpl-02', 'tpl-08', 'tpl-15', 'tpl-58'] },
  { text: '晚安', aliases: [], templateIds: ['tpl-53', 'tpl-55', 'tpl-46', 'tpl-21'] },
  { text: '早安', aliases: [], templateIds: ['tpl-14', 'tpl-56', 'tpl-12', 'tpl-60'] },
  { text: '加油', aliases: [], templateIds: ['tpl-03', 'tpl-04', 'tpl-13', 'tpl-48'] },
  { text: '好的', aliases: ['好'], templateIds: ['tpl-06', 'tpl-09', 'tpl-01', 'tpl-15'] },
  { text: '可以', aliases: [], templateIds: ['tpl-06', 'tpl-09', 'tpl-14', 'tpl-48'] },
  { text: '抱歉', aliases: [], templateIds: ['tpl-43', 'tpl-44', 'tpl-42', 'tpl-58'] },
  { text: '对不起', aliases: [], templateIds: ['tpl-43', 'tpl-44', 'tpl-42', 'tpl-58'] },
  { text: '再见', aliases: [], templateIds: ['tpl-53', 'tpl-59', 'tpl-55', 'tpl-60'] },
  { text: '开心', aliases: [], templateIds: ['tpl-01', 'tpl-07', 'tpl-10', 'tpl-16'] },
  { text: '哈哈', aliases: ['哈哈哈'], templateIds: ['tpl-16', 'tpl-31', 'tpl-25', 'tpl-51'] },
  { text: '喜欢', aliases: [], templateIds: ['tpl-49', 'tpl-52', 'tpl-58', 'tpl-50'] },
  { text: '爱你', aliases: [], templateIds: ['tpl-49', 'tpl-52', 'tpl-58', 'tpl-50'] },
  { text: '生气', aliases: [], templateIds: ['tpl-34', 'tpl-35', 'tpl-36', 'tpl-40'] },
  { text: '不要', aliases: [], templateIds: ['tpl-33', 'tpl-37', 'tpl-24', 'tpl-32'] },
  { text: '快点', aliases: [], templateIds: ['tpl-38', 'tpl-39', 'tpl-54', 'tpl-40'] },
  { text: '无语', aliases: [], templateIds: ['tpl-17', 'tpl-20', 'tpl-28', 'tpl-32'] },
  { text: '收到', aliases: [], templateIds: ['tpl-06', 'tpl-09', 'tpl-15', 'tpl-48'] },
  { text: '在吗', aliases: [], templateIds: ['tpl-12', 'tpl-21', 'tpl-29', 'tpl-60'] },
];

const emojiDefinitions = [
  ['happy', '开心', 'happy'], ['laugh', '大笑', 'happy'], ['wink', '眨眼', 'mischievous'], ['love', '喜欢', 'love'],
  ['proud', '骄傲', 'proud'], ['excited', '兴奋', 'excited'], ['calm', '平静', 'calm'], ['relieved', '释然', 'relieved'],
  ['surprised', '惊讶', 'surprised'], ['shocked', '震惊', 'surprised'], ['confused', '疑惑', 'confused'], ['skeptical', '怀疑', 'skeptical'],
  ['speechless', '无语', 'speechless'], ['embarrassed', '尴尬', 'embarrassed'], ['shy', '害羞', 'shy'], ['pleading', '恳求', 'pleading'],
  ['sad', '伤心', 'sad'], ['cry', '哭泣', 'sad'], ['sob', '大哭', 'sad'], ['disappointed', '失望', 'disappointed'],
  ['tired', '疲惫', 'tired'], ['sleepy', '困倦', 'sleepy'], ['exhausted', '累瘫', 'exhausted'], ['bored', '无聊', 'bored'],
  ['angry', '生气', 'angry'], ['furious', '暴怒', 'furious'], ['annoyed', '烦躁', 'annoyed'], ['grumpy', '不爽', 'annoyed'],
  ['disgusted', '嫌弃', 'disgusted'], ['afraid', '害怕', 'afraid'], ['panic', '惊慌', 'panic'], ['nervous', '紧张', 'nervous'],
  ['kiss', '亲亲', 'love'], ['hug', '拥抱', 'love'], ['party', '派对', 'happy'], ['celebrate', '庆祝', 'happy'],
  ['cool', '酷', 'proud'], ['mischievous', '坏笑', 'mischievous'], ['silly', '调皮', 'silly'], ['crazy', '疯狂', 'silly'],
  ['thinking', '思考', 'thinking'], ['idea', '灵感', 'thinking'], ['focused', '专注', 'focused'], ['determined', '坚定', 'determined'],
  ['hungry', '饥饿', 'hungry'], ['delicious', '美味', 'happy'], ['sick', '生病', 'sick'], ['dizzy', '头晕', 'dizzy'],
] as const;

await rm(templatesRoot, { recursive: true, force: true });
await rm(emojiBaseRoot, { recursive: true, force: true });
await mkdir(templatesRoot, { recursive: true });
await mkdir(emojiBaseRoot, { recursive: true });

for (let sheetIndex = 0; sheetIndex < 4; sheetIndex += 1) {
  const start = sheetIndex * 16;
  const ids = templateDefinitions.slice(start, start + 16)
    .map((_, index) => `tpl-${String(start + index + 1).padStart(2, '0')}`);
  await cropExpressionContactSheet({
    sourcePath: resolve(contactSheetsRoot, `templates-${String(sheetIndex + 1).padStart(2, '0')}.png`),
    outputRoot: templatesRoot,
    ids,
    columns: 4,
    rows: 4,
    cellSize: 384,
  });
}

for (let sheetIndex = 0; sheetIndex < 3; sheetIndex += 1) {
  const start = sheetIndex * 16;
  await cropExpressionContactSheet({
    sourcePath: resolve(contactSheetsRoot, `emoji-${String(sheetIndex + 1).padStart(2, '0')}.png`),
    outputRoot: emojiBaseRoot,
    ids: emojiDefinitions.slice(start, start + 16).map(([id]) => id),
    columns: 4,
    rows: 4,
    cellSize: 384,
  });
}

const templates = templateDefinitions.map(([keyword, emotion], index) => {
  const id = `tpl-${String(index + 1).padStart(2, '0')}`;
  return {
    id,
    type: index < 20 ? 'gif' : 'static',
    source: `templates/${id}.png`,
    keywords: [...keyword.split(/[，、]/), ...(recommendationAliases[id] ?? [])],
    emotions: [emotion],
    sourceCrop: { x: 0, y: 112, width: 384, height: 272 },
    textSafeArea: { x: 32, y: 32, width: 448, height: 160 },
    layout: {
      minFontSize: 24,
      maxFontSize: 52,
      textColor: '#ffffff',
      strokeColor: '#000000',
      strokeWidth: 3,
      alignment: 'center',
      maxLines: 2,
    },
  };
});
const emojiBases = emojiDefinitions.map(([id, name, emotion]) => ({
  id,
  name,
  emotions: [emotion],
  source: `emoji-base/${id}.png`,
}));
const highFrequencyIds = emojiDefinitions.slice(0, 16).map(([id]) => id);
const highFrequencyCombinations = highFrequencyIds.flatMap((firstId, index) => (
  highFrequencyIds.slice(index, index + 2).map((secondId) => `${firstId}__${secondId}`)
));
const manifest = {
  version: EXPRESSION_CATALOG_VERSION,
  expectedCounts: { templates: 60, animatedTemplates: 20, emojiBases: 48 },
  prebuiltPhrases,
  builtInTemplateIds: templates.slice(0, 12).map(({ id }) => id),
  highFrequencyCombinations,
  templates,
  emojiBases,
};
await writeFile(
  resolve(sourceRoot, 'manifest.source.json'),
  `${JSON.stringify(manifest, null, 2)}\n`,
);
console.log(`已裁切并生成源清单：${templates.length} templates, ${emojiBases.length} emoji bases`);
