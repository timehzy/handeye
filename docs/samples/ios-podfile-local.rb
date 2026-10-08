# 在你的 Podfile 中：以本地源码方式引入 handeye KMP framework（debug 装配能力的载体）。
# :path 指向 handeye 仓库相对/绝对路径；framework 名以 device-kmp 产物为准。
target 'YourApp' do
  pod 'HandeyeDeviceKmp', :path => '../handeye/device-kmp', :configuration => ['Debug']
end
# 注意：走二进制发布物时 debug 装配能力不会进包——scenario 会全部 FAIL。
# 用 scripts/run.sh --check-integration 可诊断。
