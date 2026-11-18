package app.auri.capture.control

/**
 * 耳机控制通道（系统媒体会话）的状态，只回答“应用有没有向系统要到并占着这个通道”：
 *  - [OFF]：控制模式没开，没有占着；
 *  - [ACTIVE]：已经向系统要到，在等按键；
 *  - [UNAVAILABLE]：控制模式开着，但系统没给（耳机控制不可用，屏幕按钮仍然可用）。
 * 它不是“耳机手势一定能到达”的保证：系统不会通知应用别的应用是否抢走了媒体按键，到不到达要靠校准结果说话（FR-HS-005）。
 */
enum class HeadsetControlStatus { OFF, ACTIVE, UNAVAILABLE }
