export interface DeliveryRule {
  id: string; packageName: string; mimeTypes: string[]; minVersionCode: number; maxVersionCode: number | null;
  versionName: string | null; minSdk: number; enabled: boolean; method: 'commit_content' | 'private_command';
  requireCompatIme: boolean; requiredEditorExtras: Record<string, number>; action: string | null; uriKey: string | null;
}
export interface DeliveryConfig { schemaVersion: 1; revision: number; rules: DeliveryRule[] }
export interface DeliveryState { current: DeliveryConfig; history: DeliveryConfig[] }
