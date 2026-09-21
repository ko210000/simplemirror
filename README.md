# 极简镜子

打开就是前置摄像头全屏预览，支持拍照、相册和补光调光，屏幕常亮。

**镜像默认关闭**：画面与普通相机一致，任何场景文字都正读。对着天花板、墙面这类
场景时，水平镜像会让整幅画面相对肉眼所见左右翻转、文字反写，所以默认不镜像；
需要照自己时可在设置页打开「镜像模式」（`PreviewView.scaleX = -1f`），即时生效。
细节见下面「踩过的坑」第 1 条。

## 已实现

- 相机权限申请，覆盖「首次拒绝」和「不再询问」两条分支
- CameraX 前置摄像头全屏预览
- 拍照：走内存回调自行旋转落盘，规避设备 HAL 旋转与分辨率缺陷（见坑 3）
- 照片存储可选：应用私有目录（默认）或存入公共相册 `Pictures/极简镜子`
  （MediaStore，API 29+，无需存储权限；失败自动退回私有目录）
- 应用内相册：网格浏览、查看大图、长按删除，同时聚合私有目录与公共相册
  两个来源；删除兼容文件路径与 content uri
- 设置页：镜像模式、屏幕常亮、存入公共相册，改动即时生效无需重启
- 夜间补光：一键把屏幕亮度拉满 + 相机曝光补偿拉到设备支持的最大值
- 主界面两侧竖向滑条：左侧手动调屏幕亮度（初始值取系统亮度），
  右侧手动调相机曝光（范围取设备实际支持值，不支持则禁用）
- 切后台自动释放相机、回前台自动恢复；无前置摄像头、相机被占用时的降级提示
- 旋转屏幕不重建 Activity、相机不重新申请，拍摄方向随旋转同步



## 环境要求

- JDK 17 或 21（AGP 8.4.0 不支持 JDK 25，构建前确认 `JAVA_HOME` 指向 17/21）
- Android SDK，路径写在 `local.properties`（当前为 `D:\Android\sdk`）

## 构建与安装

```bash
# 编译
./gradlew.bat :app:assembleDebug

# 安装到已连接设备
./gradlew.bat :app:installDebug

# 或手动指定设备
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

## 代码结构

```
app/src/main/java/com/fei/simplemirror/
├─ MainActivity.kt                  窗口（全屏/常亮/亮度）、权限流程、提示层、滑条与补光联动
├─ SettingsActivity.kt              镜像/常亮/存储位置开关，广播通知主页即时生效
├─ PhotoGalleryActivity.kt          应用内相册网格，聚合两个存储来源，长按删除
├─ PhotoViewerActivity.kt           大图查看与删除
├─ PhotoAdapter.kt                  相册网格适配器（缩略图解码兼容路径与 content uri）
├─ VerticalSeekBar.kt               竖向进度条（绘制与触摸轴交换的经典旋转实现）
└─ camera/MirrorCameraController.kt CameraX 绑定、拍照落盘、曝光/补光、错误上报、重试
```

生命周期上不需要手写解绑：把 Activity 作为 `LifecycleOwner` 传给
`ProcessCameraProvider.bindToLifecycle()` 之后，CameraX 会在 `ON_STOP` 释放相机、
`ON_START` 重新获取。

## 踩过的坑（都已在真机上验证）

**1. 镜像想用 `scaleX` 的话，PreviewView 必须走 TextureView**

`PreviewView` 默认的 `PERFORMANCE` 实现模式底层是 `SurfaceView`，画面由独立图层合成，
不响应 View 层的 `scaleX`。实测把 `scaleX` 在 `1f` 和 `-1f` 之间切换，同一场景的截图
一模一样、完全没有翻转。要让镜像生效，必须加 `app:implementationMode="compatible"`
改走 `TextureView`（代价是略微多一点功耗）。

设置页的镜像开关就是靠 `scaleX` 切换的，实现模式已改为 `compatible`。

**2. 从系统弹窗或设置页返回后，系统栏会重新出现**

只在 `onCreate` 里调一次 `WindowInsetsControllerCompat.hide()` 是不够的：系统权限弹窗
或「去设置」页面关闭后，系统会把状态栏/导航栏恢复显示。补在
`onWindowFocusChanged(hasFocus = true)` 里重新隐藏。

**3. 部分机型前置摄像头：设了 `targetRotation` 拍照必失败，最大分辨率 JPEG 错位**

真机上遇到两个叠加的问题：给 `ImageCapture` 设置 `targetRotation` 后，每次拍照都报
`Processing failed`（该 ROM 的 HAL 处理不了旋转请求）；不设则照片按传感器原始横向
保存、方向错乱。另外用最大分辨率时拍出的 JPEG 内容还会错位拼接。

当前的解法：不给 `ImageCapture` 设 `targetRotation`，把拍摄分辨率限制到 1600×1200
附近，拍照走内存回调拿到 JPEG 后按 `rotationDegrees` 自己解码→旋转→重编码再落盘，
方向不依赖 HAL 写 EXIF，照片永远是正立的。

**（附）判断镜像方向别靠肉眼估位置**

用场景里的印刷文字做判据最可靠：不镜像时文字正读，镜像后文字反写。位置类的判据
（物体在画面左边还是右边）在手机姿态变了之后就无法对比了。

## 已知待办

- 应用图标目前是普通矢量图，不是自适应图标（adaptive icon），在部分启动器上会被系统加白底
- 上架前需要把 `compileSdk` / `targetSdk` 提到 35 或 36（Google Play 对新应用的要求），
  届时 AGP 也要一起升到 8.7 以上
