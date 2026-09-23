/** 服务端生成的重复/删除收据保留 fingerprint，但不重复进入消息列表和统计。 */
export function visibleChatMessage(alias = 'm'): string {
  return `COALESCE(${alias}.metadata->>'screenshot_duplicate_of','')='' AND COALESCE(${alias}.metadata->>'screenshot_deleted','')<>'true'`;
}
