# GPT 真机恢复与独立安装设计

用户已确认关闭时出现系统确认框，并批准 `polished-snuggling-karp.md` 计划。此修订仅在 gpt 分支实施，不修改 main。

## 问题与权限边界

旧控制器把 disable/enable 的 false 返回当作立即终态，并可能丢失广播与快照不同步的状态，开启截止时间也不读取最终 ON。这些是已核实的代码缺口，不等于已证明其是 HyperOS 4 真机故障的根因。

`BLUETOOTH_PRIVILEGED` 是系统签名/特权权限，普通侧载 APK 无法向用户申请获得。不会伪造安全控制授权、调用私有 AppOps 或引入 Root/Shizuku。系统确认框只能按 OEM 提供的实际权限设置处理，不能保证消除。

## 行为

保持初始关闭退出、不连接手环、实时广播名称精确匹配和扫描15秒。首次有效匹配短 Toast 显示“已发现目标手环，准备重启蓝牙”，只出现一次，不需要确认。

关闭请求最多一次，返回 false 或异常不当作最终状态；以250ms精确状态轮询及广播提示观察，关闭/用户确认窗口45秒。首次确认真正 OFF 后从单调时钟起算至少3000ms，再请求开启一次，开启观察窗口15秒。过渡状态或UNKNOWN不冒充OFF/ON；延时后暂时UNKNOWN可在窗口内重读。截止时读取最终状态，不因为遗漏广播误报失败。未恢复以短Toast提示手动开启。

透明 Activity 在服务 ready 后继续存活，终态才结束，最长约90秒；ready/terminal握手去重且支持配置变化。私有前台服务保留connectedDevice|location、短通知、唤醒锁和终态资源清理。进程强杀、权限撤销、OEM拒绝或迟于窗口的用户确认均不保证恢复，不添加常驻/闹钟重启逻辑。

## 独立身份与交付

包名/namespace/Java/test：`com.blreay.bandreset.gpt`；入口：`com.blreay.bandreset.gpt.BandResetActivity`；action：`com.blreay.bandreset.gpt.RUN`；标题“蓝牙手环重启GPT”。保留target32/min26/compile35。新旧版本可同时安装，但新包需重新授权。

保留现有Actions构建检查，仅gpt push成功后通过contents:write专属发布任务创建`gpt-v<run_number>` release，明确绑定SHA、make_latest=false，资产`band-reset-gpt.apk`和SHA256。不覆盖main的latest，不读取/复制main的密钥。此修订仍为debug签名，未来签名变化可能需卸载再安装。最终提供实际已上传可访问的APK browser_download_url。

## 验证

先用虚拟单调时钟写回归测试，覆盖false后迟到OFF、无OFF拒绝、无广播轮询、OFF满三秒、false后ON、截止ON、暂时/持久UNKNOWN、重复/取消/旧回调及ready/terminal竞态。执行clean测试lint构建，检查签名与新身份，独立审查后推送并验证CI和下载地址。真机仍需再次验收，不用构建通过代替OEM允许切换。
