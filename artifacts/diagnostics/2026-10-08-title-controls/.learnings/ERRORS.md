## ERR-20261008-ENV

**Priority**: low
**Status**: resolved
**Area**: infra

### Summary
本机python没有Pillow；sharp包不能猜lib/index.js入口；Gradle -p后-I相对路径从项目目录解析。

### Resolution
原图只读像素检查使用server目录import sharp；回放初始化脚本使用绝对路径。不新增依赖、不修改用户环境。
