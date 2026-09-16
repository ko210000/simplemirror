# 极简镜子

打开就是前置摄像头全屏预览，屏幕常亮。当前是最小 Demo 阶段。

**预览不做镜像**：画面与普通相机一致，任何场景文字都正读。曾经实现过水平镜像
（`PreviewView.scaleX = -1f`），但对着天花板、墙面这类场景时整幅画面相对肉眼所见
会左右翻转、文字反写，所以去掉了。若要重新做成开关，先看下面「踩过的坑」第 1 条。

## 已实现

- 相机权限申请，覆盖「首次拒绝」和「不再询问」两条分支
- CameraX 前置摄像头全屏预览
- 屏幕常亮（`FLAG_KEEP_SCREEN_ON`）
- 切后台自动释放相机、回前台自动恢复
- 无前置摄像头、相机被占用时的降级提示
- 旋转屏幕不重建 Activity、相机不重新申请

## 尚未实现

拍照、冻结、对焦、缩放、曝光调节、滤镜、录像、设置页。

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
├─ MainActivity.kt                  窗口（全屏/常亮）、权限流程、提示层
└─ camera/MirrorCameraController.kt CameraX 绑定、错误上报、重试
```

生命周期上不需要手写解绑：把 Activity 作为 `LifecycleOwner` 传给
`ProcessCameraProvider.bindToLifecycle()` 之后，CameraX 会在 `ON_STOP` 释放相机、
`ON_START` 重新获取。

## 两个踩过的坑（都已在真机上验证）

**1. 镜像想用 `scaleX` 的话，PreviewView 必须走 TextureView**

`PreviewView` 默认的 `PERFORMANCE` 实现模式底层是 `SurfaceView`，画面由独立图层合成，
不响应 View 层的 `scaleX`。实测把 `scaleX` 在 `1f` 和 `-1f` 之间切换，同一场景的截图
一模一样、完全没有翻转。要让镜像生效，必须加 `app:implementationMode="compatible"`
改走 `TextureView`（代价是略微多一点功耗）。

当前代码没开镜像，所以用的是默认的 `PERFORMANCE`（省电，对常亮预览更重要）。
已验证 `PERFORMANCE` 下提示层能正常盖在实时预览之上。

**2. 从系统弹窗或设置页返回后，系统栏会重新出现**

只在 `onCreate` 里调一次 `WindowInsetsControllerCompat.hide()` 是不够的：系统权限弹窗
或「去设置」页面关闭后，系统会把状态栏/导航栏恢复显示。补在
`onWindowFocusChanged(hasFocus = true)` 里重新隐藏。

**（附）判断镜像方向别靠肉眼估位置**

用场景里的印刷文字做判据最可靠：不镜像时文字正读，镜像后文字反写。位置类的判据
（物体在画面左边还是右边）在手机姿态变了之后就无法对比了。

## 已知待办

- 应用图标目前是普通矢量图，不是自适应图标（adaptive icon），在部分启动器上会被系统加白底
- 上架前需要把 `compileSdk` / `targetSdk` 提到 35 或 36（Google Play 对新应用的要求），
  届时 AGP 也要一起升到 8.7 以上
  
