package cn.edu.jxau.tools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.edu.jxau.tools.ui.login.LoginScreen
import cn.edu.jxau.tools.ui.theme.JxauTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JxauTheme {
                LoginScreen()
            }
        }
    }
}
