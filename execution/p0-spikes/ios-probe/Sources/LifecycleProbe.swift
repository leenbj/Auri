import UIKit

/// 记录前后台、锁屏（受保护数据可用性）、内存警告与终止通知，用来给其他事件标注生命周期。
final class LifecycleProbe {
    private var tokens: [NSObjectProtocol] = []

    func start() {
        let logger = ProbeLogger.shared
        let center = NotificationCenter.default

        func watch(_ name: Notification.Name, _ kind: String, state: String? = nil) {
            tokens.append(center.addObserver(forName: name, object: nil, queue: nil) { _ in
                if let state { logger.setAppState(state) }
                logger.log(kind)
            })
        }

        watch(UIApplication.didBecomeActiveNotification, "app_did_become_active", state: "active")
        watch(UIApplication.willResignActiveNotification, "app_will_resign_active", state: "inactive")
        watch(UIApplication.didEnterBackgroundNotification, "app_did_enter_background", state: "background")
        watch(UIApplication.willEnterForegroundNotification, "app_will_enter_foreground", state: "inactive")
        watch(UIApplication.protectedDataWillBecomeUnavailableNotification, "protected_data_unavailable")
        watch(UIApplication.protectedDataDidBecomeAvailableNotification, "protected_data_available")
        watch(UIApplication.didReceiveMemoryWarningNotification, "memory_warning")
        watch(UIApplication.willTerminateNotification, "app_will_terminate")
    }
}
