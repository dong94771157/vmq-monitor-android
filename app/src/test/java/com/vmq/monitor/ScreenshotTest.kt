package com.vmq.monitor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import com.vmq.monitor.data.ConfigStore
import com.vmq.monitor.data.LogStore
import com.vmq.monitor.data.NoticeLog
import com.vmq.monitor.data.StateStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * 把界面渲染成 PNG，用来肉眼检查布局。
 *
 * 不是断言测试 —— 它只负责产出图片（`build/screenshots/` 下的 PNG）。
 * 之所以走 Robolectric 而不是模拟器：arm64 镜像的 AVD 在 x86 主机上起不来。
 * NATIVE 图形模式走真实 Skia 绘制，中文字体也能正常出字。
 *
 * 一次跑出三张：空状态 / 运行中（带日志）/ 配置页。
 * 空状态和运行中放在同一个用例里，避免跨用例复用单例 DB 句柄。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h1180dp-xxhdpi")
class ScreenshotTest {

    private fun capture(scenario: ActivityScenario<MainActivity>, name: String) {
        scenario.onActivity { activity ->
            shadowOf(Looper.getMainLooper()).idle()
            val root = activity.window.decorView
            if (root.width == 0 || root.height == 0) {
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))

            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun `capture scan page`() {
        // 只 inflate 布局、不启动 Activity：走 onResume 会去开相机，Robolectric 里没有。
        // 取景框由 ViewfinderView 在 onSizeChanged 里算，所以必须真的 measure + layout。
        val themed = android.view.ContextThemeWrapper(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            R.style.Theme_VmqMonitor_Scan
        )
        val view = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.activity_scan, null)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "scan.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun `capture empty and running states`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            capture(scenario, "monitor_empty")

            scenario.onActivity { it.openConfigTab() }
            capture(scenario, "config_empty")

            scenario.onActivity { activity ->
                activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
                    .currentItem = 2
            }
            capture(scenario, "about")

            // 灌一份"看起来正常"的数据：地址 + 密钥 + 两个通道 + 心跳 + 三条不同结果的日志
            scenario.onActivity { activity ->
                val cfg = ConfigStore.get(activity)
                cfg.host = "pay.example.com"
                cfg.commKey = "testkey0001"
                cfg.setPassage(1, "8")
                cfg.setPassage(2, "9")
                cfg.serviceEnabled = true

                val now = System.currentTimeMillis() / 1000
                val state = StateStore.get(activity)
                state.setLastHeart(now - 6)
                state.setHeartFail(0)

                val logs = LogStore.get(activity)
                logs.clear()
                // 按时间从旧到新插：recent() 是 ORDER BY id DESC，先插的排后面
                logs.insert(
                    NoticeLog(
                        ts = now - 260, platform = 0, pkg = "com.android.systemui",
                        title = "截图", content = "已保存截图",
                        amount = null, status = "未匹配"
                    )
                )
                logs.insert(
                    NoticeLog(
                        ts = now - 95, platform = 2, pkg = "com.eg.android.AlipayGphone",
                        title = "收钱码", content = "通过扫码向你付款0.12元",
                        amount = "0.12", status = "未配置通道"
                    )
                )
                logs.insert(
                    NoticeLog(
                        ts = now - 8, platform = 1, pkg = "com.tencent.mm",
                        title = "微信支付", content = "微信支付收款18.00元",
                        amount = "18.00", status = "已入队"
                    )
                )

                activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
                    .currentItem = 0
            }
            // 面板每 2 秒自刷一次，把时钟推过去让它读到新数据
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            capture(scenario, "monitor_running")
        }
    }
}
