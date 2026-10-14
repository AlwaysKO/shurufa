// 虚构素材与设备；不连接真实业务服务。
const userId='aaaaaaaa-0000-4000-8000-000000000001';
const long='虚构长名称'.repeat(5)+'LongUnbrokenName'.repeat(5);
const image='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
const device={id:userId,dashboard_name:long,brand:'Fixture',model:'Test',dashboard_tags:['测试'],last_seen_at:'2026-10-14T08:00:00Z'};
const group={keyword:'测试',aliases:['测试',long],confirmedAliases:[],category:'其他',planned:false,custom:false,assets:[{id:7,source:'personal',url:image,format:'gif',keywords:['测试'],width:1,height:1,useCount:0},{id:'system',source:'system',url:image,format:'gif',keywords:['测试'],width:1,height:1,useCount:null}]};
const material={sha256:'a'.repeat(64),ids:[7],keywords:[long+'未分组'],url:image,format:'gif',width:1,height:1,assigned:true};
const asset={id:'mine',name:long,source:'personal',deletable:true,url:image,width:240,height:240,format:'gif',sha256:'test',sourceStatement:long,textSafeArea:{x:6,y:190,width:228,height:44},layout:{minFontSize:12,maxFontSize:24,textColor:'#222222',strokeColor:'#ffffff',strokeWidth:1,alignment:'center',maxLines:2}};
const data={
 '/api/v1/auth/session':{username:'fixture'},
 '/api/v1/dashboard/users':{users:[device],total:1,page:1,page_size:12},
 '/api/v1/dashboard/devices':{devices:[device]},
 '/api/v1/dashboard/sticker-library':{groups:[group],systemCount:1,personalCount:1,warnings:[]},
 '/api/v1/dashboard/sticker-materials':{items:[material],total:1,page:1,pageSize:40,warnings:[]},
 '/api/v1/dashboard/sticker-import/agents':{agents:[]},
 '/api/v1/dashboard/sticker-import/jobs':{jobs:[]},
 '/api/v1/dashboard/synthesis-library':{assets:[asset,{...asset,id:'second'}],total:2},
 '/api/v1/dashboard/dictionary/devices':{devices:[{...device,device_id:userId,in_group:true,restore_enabled:true,synced:true,additions_supported:true,habits_supported:true,migration_status:'complete',imported:1}]},
 '/api/v1/dashboard/dictionary/entries':{entries:[{device_id:userId,kind:'merged',text:'测试词',code:'test',pinyin:'ce shi ci',source:'dashboard',count:1,weight:1,last_used:0,status:'enabled'}],total:1,total_words:1,page:1},
};
module.exports={userId,long,image,device,group,material,asset,data};
