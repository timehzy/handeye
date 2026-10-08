# 前提声明（先读这段）：
# handeye 仓库当前【未附带 podspec，也不产出 iOS framework 产物】——device-kmp 只有
# ios target 声明，未配置 frameworks {} 与 CocoaPods 插件。本样例展示的是接入【形态】：
# pod install 之前需要先有 podspec（在你的工程里自建，或等 handeye 后续提供）。
# 最小 podspec 骨架（放进 device-kmp/ 目录，framework 名以你实际的产物为准）：
#
#   Pod::Spec.new do |s|
#     s.name                = 'HandeyeDeviceKmp'
#     s.version             = '0.1.0'
#     s.summary             = 'handeye device-side debug framework'
#     s.homepage            = 'https://example.com/handeye'
#     s.license             = { :type => 'MIT' }
#     s.author              = { 'handeye' => 'dev@example.com' }
#     s.source              = { :path => '.' }   # 本地源码，无需 tag
#     s.vendored_frameworks = 'build/XCFrameworks/debug/HandeyeDeviceKmp.xcframework' # 以实际产物路径为准
#     s.ios.deployment_target = '14.0'
#   end
#
# 在你的 Podfile 中：以本地源码方式引入 handeye KMP framework（debug 装配能力的载体）。
# :path 指向 handeye 仓库相对/绝对路径；framework 名以 device-kmp 产物为准。
target 'YourApp' do
  pod 'HandeyeDeviceKmp', :path => '../handeye/device-kmp', :configuration => ['Debug']
end
# 注意：走二进制发布物时 debug 装配能力不会进包——/health 探测不通，
# setup_ios.sh 退出码 4（setup 阶段失败，scenario 不进入执行）。
# 用 scripts/run.sh --check-integration 可诊断。
