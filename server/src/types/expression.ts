export const EXPRESSION_ASSET_TYPES = ['prebuilt', 'synthesis-template'] as const;
export type ExpressionAssetType = typeof EXPRESSION_ASSET_TYPES[number];

export const EXPRESSION_ASSET_FORMATS = ['gif', 'png', 'jpg', 'jpeg', 'webp'] as const;
export type ExpressionAssetFormat = typeof EXPRESSION_ASSET_FORMATS[number];

export interface ExpressionTextSafeArea {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface ExpressionTextLayout {
  minFontSize: number;
  maxFontSize: number;
  textColor: string;
  strokeColor: string;
  strokeWidth: number;
  alignment: 'start' | 'center' | 'end';
  maxLines: number;
}

export interface ExpressionAsset {
  id: string;
  type: ExpressionAssetType;
  format: ExpressionAssetFormat;
  version: string;
  fileName: string;
  thumbnailFileName: string | null;
  sha256: string;
  width: number;
  height: number;
  keywords: string[];
  emotions: string[];
  embeddedText: string | null;
  textSafeArea: ExpressionTextSafeArea | null;
  layout: ExpressionTextLayout | null;
  heat: number;
}

export interface EmojiBase {
  id: string;
  name: string;
  emotions: string[];
  fileName: string;
  sha256: string;
  version: string;
  width: number;
  height: number;
  sortOrder: number;
}

export interface EmojiCombination {
  key: string;
  firstId: string;
  secondId: string;
  fileName: string;
  sha256: string;
  version: string;
  width: number;
  height: number;
  heat: number;
}
