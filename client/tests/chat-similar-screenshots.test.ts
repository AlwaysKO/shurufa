import { expect, it } from '../../server/node_modules/vitest/dist/index.js';
import { groupSimilarScreenshots } from '../src/chatSimilarScreenshots';
import type { ChatMessageRow } from '../src/api';
const shot=(id:string,seconds:number,overrides:Partial<ChatMessageRow>={}):ChatMessageRow=>({id,conversation_id:1,device_id:'phone-a',platform:'wechat',direction:'system',message_type:'image',sender_key:'self',sender_name:null,text:'截图',displayed_time:null,occurred_at:null,captured_at:new Date(Date.parse('2026-10-08T10:00:00Z')+seconds*1000).toISOString(),sequence_hint:null,metadata:{capture_kind:'conversation_screenshot'},assets:[{id:Number(id),sha256:id,mime_type:'image/webp',width:1080,height:2200,role:'content',position:0,url:`/${id}.webp`,perceptual_hash:'123456789abcdef0'}],...overrides});
it('只折叠页内连续有可靠证据的相似截图，保留全部原始对象',()=>{
 const a=shot('1',60),b=shot('2',50),c=shot('3',45);const groups=groupSimilarScreenshots([a,b,c]);expect(groups).toHaveLength(1);expect(groups[0].messages).toEqual([a,b,c]);expect(groups[0].messages[1]).toBe(b);
});
it.each(['missing-hash','bad-hash','flat-hash','missing-device','other-device','other-conversation','other-platform','other-size','missing-size','not-screenshot','same-time','reverse-time','too-old','different-hash'])('%s不推测相似',reason=>{
 const a=shot('1',60),b=shot('2',50);
 if(reason==='missing-hash')delete b.assets[0].perceptual_hash;
 if(reason==='bad-hash')b.assets[0].perceptual_hash='123';
 if(reason==='flat-hash')a.assets[0].perceptual_hash=b.assets[0].perceptual_hash='0000000000000000';
 if(reason==='missing-device')delete b.device_id;
 if(reason==='other-device')b.device_id='phone-b';
 if(reason==='other-conversation')b.conversation_id=2;
 if(reason==='other-platform')b.platform='qq';
 if(reason==='other-size')b.assets[0].height=2300;
 if(reason==='missing-size')b.assets[0].width=null;
 if(reason==='not-screenshot')b.metadata={};
 if(reason==='same-time')b.captured_at=a.captured_at;
 if(reason==='reverse-time')b.captured_at=shot('9',70).captured_at;
 if(reason==='too-old')b.captured_at=shot('9',-1).captured_at;
 if(reason==='different-hash')b.assets[0].perceptual_hash='fedcba9876543210';
 expect(groupSimilarScreenshots([a,b])).toHaveLength(2);
});
it('正文行阻断分组，不跨过滤后隐藏的正文串组',()=>{
 const text=shot('2',50,{message_type:'text',metadata:{},assets:[],text:'正文'});expect(groupSimilarScreenshots([shot('1',60),text,shot('3',40)])).toHaveLength(3);
});
it('不用逐张接近累积成跨度大组，也不把跨页截图传入下一组',()=>{
 const a=shot('1',100),b=shot('2',50),c=shot('3',0);expect(groupSimilarScreenshots([a,b,c]).map(g=>g.messages.length)).toEqual([2,1]);
 expect(groupSimilarScreenshots([c])).toHaveLength(1);
 const d=shot('4',90),e=shot('5',80);d.assets[0].perceptual_hash='123456789abcdef3';e.assets[0].perceptual_hash='123456789abcdefF';expect(groupSimilarScreenshots([a,d,e]).map(g=>g.messages.length)).toEqual([2,1]);
});
it('图片消息带非占位正文时独立展示并阻断相邻折叠',()=>{
 const a=shot('1',60),b=shot('2',50,{text:'明天下午三点见'}),c=shot('3',40);
 expect(groupSimilarScreenshots([a,b,c]).map(g=>g.messages.length)).toEqual([1,1,1]);
 for(const text of ['图片说明：转账金额100元','聊天截图\n新消息','相同的真实正文'])expect(groupSimilarScreenshots([shot('4',30,{text}),shot('5',20,{text})])).toHaveLength(2);
});
it.each([null,'','图片','截图','聊天截图'])('明确无正文或历史占位%s仍可提示相似',text=>{
 expect(groupSimilarScreenshots([shot('1',60,{text}),shot('2',50,{text})])).toHaveLength(1);
});
