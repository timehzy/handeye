# handeye 配置模板。复制为 scripts/handeye.local.sh 后按你的工程修改（local.sh 已 gitignore）。
# 读取优先级：环境变量 > handeye.local.sh > 本文件默认值。
# 本文件提交的即是 demo 的出厂配置——接入者要写的配置与 demo 同款。

# ---- 通用 ----
: "${APP_ID:=dev.handeye.demo}"            # Android 包名 / iOS bundle id（demo 两者相同）
: "${HANDEYE_DEEPLINK_SCHEME:=handeye}"    # 冷启动进态 deeplink 的 scheme

# ---- Android ----
: "${ANDROID_GRADLE_TASK:=:demo-android:app:assembleDebug}"
: "${ANDROID_APK_GLOB:=demo-android/app/build/outputs/apk/debug/*.apk}"
: "${ANDROID_MAIN_ACTIVITY:=.MainActivity}"
# 新鲜度判定监听的源码路径（空格分隔，相对仓库根；改动晚于装包时间则判定陈旧）
: "${SOURCE_PATHS_ANDROID:=demo-android/app/src device-kmp/src}"
: "${ANDROID_SERIAL:=}"                    # 多机时指定；空 = adb devices 第一台

# ---- iOS ----
: "${IOS_PROJECT_DIR:=demo-ios/}"          # iOS 接入工程目录（含 xcodeproj/workspace 与 Podfile）；相对仓库根或绝对路径
: "${IOS_SCHEME:=handeye-demo}"            # xcodebuild -scheme
: "${IOS_WORKSPACE:=}"                     # 非空用 -workspace，否则 -project（自动探测 xcodeproj）
: "${IOS_DEVICE_PORT:=27778}"              # device 端 debug server 固定端口（约定，不探测）
: "${HANDEYE_UDID:=}"                      # 多机时指定；空 = idevice_id -l 第一台

# ---- 素材 ----
: "${FIXTURES_JSON:=fixtures/e2e.local.json}"   # 不存在时回退 fixtures/e2e.example.json
