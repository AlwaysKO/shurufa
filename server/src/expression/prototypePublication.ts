import { randomUUID } from 'node:crypto';
import { access, mkdir, readFile, rename, rm } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';

import sharp from 'sharp';

import type { PrototypeManifest } from './prototypeManifest.js';

async function pathExists(path: string): Promise<boolean> {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

/** 在任何渲染前检查清单中的全部姿势源是可解码、有透明度的 PNG。 */
export async function preflightPrototypePoses(
  manifest: PrototypeManifest,
  prototypesRoot: string,
): Promise<Map<string, string[]>> {
  const pathsById = new Map<string, string[]>();
  for (const item of manifest.items) {
    const paths: string[] = [];
    for (const poseFile of item.poseFiles) {
      const path = join(prototypesRoot, poseFile);
      let source: Buffer;
      try {
        source = await readFile(path);
      } catch (error) {
        throw new Error(`样板 ${item.id} 姿势 ${poseFile} 不可读`, { cause: error });
      }
      let metadata;
      try {
        metadata = await sharp(source).metadata();
      } catch (error) {
        throw new Error(`样板 ${item.id} 姿势 ${poseFile} 不是可解码 PNG`, { cause: error });
      }
      if (metadata.format !== 'png') {
        throw new Error(`样板 ${item.id} 姿势 ${poseFile} 必须是 PNG`);
      }
      if (!metadata.hasAlpha) {
        throw new Error(`样板 ${item.id} 姿势 ${poseFile} 缺少 alpha 通道`);
      }
      const decoded = await sharp(source).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
      let visible = false;
      let transparent = false;
      for (let offset = 3; offset < decoded.data.length; offset += decoded.info.channels) {
        const alpha = decoded.data[offset];
        if (alpha >= 8) visible = true;
        if (alpha < 255) transparent = true;
        if (visible && transparent) break;
      }
      if (!visible || !transparent) {
        throw new Error(`样板 ${item.id} 姿势 ${poseFile} 没有有效 alpha 前景与透明背景`);
      }
      paths.push(path);
    }
    pathsById.set(item.id, paths);
  }
  return pathsById;
}

/** 在目标同级临时目录完整构建，成功后才替换旧发布目录。 */
export async function publishDirectoryAtomically(
  outputRoot: string,
  build: (temporaryRoot: string) => Promise<void>,
): Promise<void> {
  const parent = dirname(outputRoot);
  const name = basename(outputRoot);
  const suffix = `${process.pid}-${randomUUID()}`;
  const temporaryRoot = join(parent, `.${name}.tmp-${suffix}`);
  const backupRoot = join(parent, `.${name}.backup-${suffix}`);
  await mkdir(parent, { recursive: true });
  await mkdir(temporaryRoot);

  let oldMoved = false;
  let newPublished = false;
  try {
    await build(temporaryRoot);
    if (await pathExists(outputRoot)) {
      await rename(outputRoot, backupRoot);
      oldMoved = true;
    }
    try {
      await rename(temporaryRoot, outputRoot);
      newPublished = true;
    } catch (error) {
      if (oldMoved) {
        await rename(backupRoot, outputRoot);
        oldMoved = false;
      }
      throw error;
    }
    if (oldMoved) {
      await rm(backupRoot, { recursive: true, force: true });
      oldMoved = false;
    }
  } catch (error) {
    if (oldMoved && !(await pathExists(outputRoot))) {
      await rename(backupRoot, outputRoot);
      oldMoved = false;
    }
    throw error;
  } finally {
    if (!newPublished) await rm(temporaryRoot, { recursive: true, force: true });
    if (oldMoved) await rm(backupRoot, { recursive: true, force: true });
  }
}
