const { userId, data: common } = require('./admin-pages.fixtures.cjs');
const long = '虚构长名称与路线测试'.repeat(15) + 'longunbrokentext'.repeat(12);
const stamp = '2026-10-14T08:00:00Z';
const row = { id:'aaaaaaaa-0000-4000-8000-000000000011', title:long, note:long, platform:'wechat', kind:'payment', captured_at:stamp, received_at:stamp, width:600,height:1332, sha256:'a'.repeat(64), mime_type:'image/png', observation_kind:'confirmed_video', entered_at:Date.parse(stamp),ended_at:Date.parse(stamp)+10000,duration_ms:10000,complete:true,exit_reason:'locked',first_image_id:'aaaaaaaa-0000-4000-8000-000000000011',origin:long,destination:long,overview_at:stamp };
const rule = {id:'wechat-fixture',packageName:'com.tencent.mm',minVersionCode:0,maxVersionCode:null,enabled:true,titleIds:['title'],inputIds:['input'],bodyIds:[],backLabels:['返回'],settingsLabels:['设置'],voiceLabels:['语音'],voicePosition:'either'};
const delivery = {id:'wechat-fixture',packageName:'com.tencent.mm',mimeTypes:['image/gif'],minVersionCode:0,maxVersionCode:null,versionName:'8.0.78',minSdk:26,enabled:true,method:'private_command',requireCompatIme:true,requiredEditorExtras:{SUPPORT_SOGOU_EXPRESSION:1},action:'com.sogou.inputmethod.exp.commit',uriKey:'EXP_PATH_URI'};
const state = rule => ({current:{schemaVersion:1,revision:1,rules:[rule]},history:[]});
const data = Object.fromEntries(['/api/v1/auth/session','/api/v1/dashboard/users','/api/v1/dashboard/devices'].map(k=>[k,common[k]]));
Object.assign(data, {
 '/api/v1/dashboard/page-captures':{records:[row],total:21,page:1,page_size:20},
 '/api/v1/dashboard/video-visits':{records:[row],total:21,page:1,page_size:20},
 '/api/v1/dashboard/navigation-records':{records:[{...row,platform:'amap'}],total:21,page:1,page_size:20},
 '/api/v1/dashboard/settings/chat-capture':state(rule),
 '/api/v1/dashboard/settings/expression-delivery':state(delivery),
 '/api/v1/dashboard/settings/collector':{collector_base_url:'https://collector.example.com'},
 '/api/v1/dashboard/chat-capture-diagnostics':{device_id:userId,platforms:{wechat:{},douyin:{}}},
 '/api/v1/dashboard/chat/overview':{conversation_count:12,message_count:1,media_count:1},
 '/api/v1/dashboard/chat/conversations':{conversations:Array.from({length:12},(_,i)=>({id:i+1,platform:'wechat',account_key:'fixture',external_key:'fixture'+i,display_name:long,conversation_type:'group',identity_confidence:1,message_count:1,first_seen_at:stamp,last_seen_at:stamp,last_message_at:stamp})),total:12,page:1,page_size:100},
 '/api/v1/dashboard/chat/messages':{messages:[{id:'message-1',conversation_id:1,platform:'wechat',direction:'incoming',message_type:'image',sender_key:'fixture',sender_name:long,text:long,displayed_time:null,occurred_at:stamp,captured_at:stamp,sequence_hint:null,metadata:{},assets:[{id:1,sha256:'b'.repeat(64),mime_type:'image/png',width:600,height:1332,role:'image',position:0,url:'/api/v1/dashboard/chat/assets/1'}]}],total:1,page:1,page_size:100}
});
const pages = [['/chat-capture','.media-preview-button'],['/page-captures','.cards article'],['/video-visits','.cards article'],['/navigation-records','.cards article'],['/chat-capture-settings','.platforms article'],['/expression-delivery','.rule'],['/data','.collector-form input']];
module.exports={userId,data,pages,long};
