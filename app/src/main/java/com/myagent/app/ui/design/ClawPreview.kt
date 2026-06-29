package com.myagent.app.ui.design

import com.myagent.app.ui.MementoTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(
  name = "Memento 设计系统",
  showBackground = true,
  backgroundColor = 0xFF030303,
)
@Composable
private fun ClawComponentShowcasePreview() {
  MementoTheme {
    ClawComponentShowcase()
  }
}