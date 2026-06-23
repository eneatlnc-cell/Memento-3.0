package com.myagent.app.ui

import com.myagent.app.GatewaySkillSummary
import com.myagent.app.MainViewModel
import com.myagent.app.api.MyAgentSkill
import com.myagent.app.ui.design.ClawDetailRow
import com.myagent.app.ui.design.ClawListPanel
import com.myagent.app.ui.design.ClawPanel
import com.myagent.app.ui.design.ClawPrimaryButton
import com.myagent.app.ui.design.ClawScaffold
import com.myagent.app.ui.design.ClawStatusPill
import com.myagent.app.ui.design.ClawStatus
import com.myagent.app.ui.design.ClawTextBadge
import com.myagent.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 技能市场主页面 — 浏览、搜索和管理技能。
 * 数据来自灵机网站 API（SkillMarketplaceService）。
 */
@Composable
internal fun MarketplaceScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val skillsSummary by viewModel.skillsSummary.collectAsState()
  val isConnected by viewModel.isConnected.collectAsState()
  val marketplaceSkills by viewModel.marketplaceSkills.collectAsState()
  val marketplaceLoading by viewModel.marketplaceLoading.collectAsState()
  val marketplaceError by viewModel.marketplaceError.collectAsState()
  var selectedCategory by remember { mutableStateOf("全部") }

  val categories = listOf("全部", "办公", "创作", "编程", "生活", "学习", "工具")

  // Fetch marketplace skills on first load
  LaunchedEffect(Unit) {
    viewModel.refreshSkillsFromMarketplace()
  }

  ClawScaffold(
    contentPadding = PaddingValues(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 6.dp),
    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(13.dp),
      contentPadding = PaddingValues(bottom = 4.dp),
    ) {
      // 标题栏
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Store,
            contentDescription = "技能市场",
            modifier = Modifier.size(24.dp),
            tint = ClawTheme.colors.primary,
          )
          Text(
            text = "技能市场",
            style = ClawTheme.type.title.copy(fontSize = 16.sp),
            color = ClawTheme.colors.text,
            modifier = Modifier.weight(1f),
          )
        }
      }

      // 搜索栏（占位，后续接入搜索 API）
      item {
        ClawPanel {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Search,
              contentDescription = "搜索",
              modifier = Modifier.size(18.dp),
              tint = ClawTheme.colors.textMuted,
            )
            Text(
              text = "搜索技能…",
              style = ClawTheme.type.body,
              color = ClawTheme.colors.textSubtle,
            )
          }
        }
      }

      // 分类标签
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          categories.forEach { category ->
            val isSelected = category == selectedCategory
            ClawStatusPill(
              text = category,
              status = if (isSelected) ClawStatus.Success else ClawStatus.Neutral,
            )
          }
        }
      }

      // 已安装技能（快速入口）
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "已安装技能",
            style = ClawTheme.type.section,
            color = ClawTheme.colors.text,
          )
          Text(
            text = "查看全部 →",
            style = ClawTheme.type.caption,
            color = ClawTheme.colors.primary,
            modifier = Modifier.padding(start = 8.dp),
          )
        }
      }

      if (isConnected && skillsSummary.skills.isNotEmpty()) {
        item {
          SkillsSummaryPanel(skills = skillsSummary.skills.take(5))
        }
      } else {
        item {
          ClawPanel {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Text(
                text = "尚未连接灵机",
                style = ClawTheme.type.section,
                color = ClawTheme.colors.text,
              )
              Text(
                text = "连接到灵机后，已安装的技能将显示在这里。",
                style = ClawTheme.type.body,
                color = ClawTheme.colors.textMuted,
              )
            }
          }
        }
      }

      // 推荐技能（来自 API 或占位数据）
      item {
        Text(
          text = "推荐技能",
          style = ClawTheme.type.section,
          color = ClawTheme.colors.text,
        )
      }

      when {
        marketplaceLoading -> {
          item {
            ClawPanel {
              Text(
                text = "正在加载技能列表…",
                style = ClawTheme.type.body,
                color = ClawTheme.colors.textMuted,
              )
            }
          }
        }
        marketplaceError != null -> {
          item {
            ClawPanel {
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  text = "加载失败",
                  style = ClawTheme.type.section,
                  color = ClawTheme.colors.warning,
                )
                Text(
                  text = marketplaceError ?: "未知错误",
                  style = ClawTheme.type.body,
                  color = ClawTheme.colors.textMuted,
                )
              }
            }
          }
        }
        marketplaceSkills.isNotEmpty() -> {
          marketplaceSkills.forEach { skill ->
            item {
              MarketplaceSkillCard(skill = skill)
            }
          }
          // Bottom note
          item {
            ClawPanel {
              Text(
                text = "共 ${marketplaceSkills.size} 个技能可用",
                style = ClawTheme.type.caption,
                color = ClawTheme.colors.textSubtle,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
              )
            }
          }
        }
        else -> {
          // Fallback: placeholder cards
          val placeholderSkills = listOf(
            Triple("AI 写作助手", "帮助撰写各类文档、邮件和创意内容", "📝"),
            Triple("代码审查", "自动审查代码质量，发现潜在 Bug", "🔍"),
            Triple("日程管理", "智能管理日程、提醒和待办事项", "📅"),
            Triple("翻译助手", "多语言实时翻译，支持 50+ 语言", "🌐"),
            Triple("笔记整理", "自动整理笔记，生成结构化摘要", "📋"),
            Triple("数据分析", "快速分析数据，生成可视化报告", "📊"),
          )
          placeholderSkills.forEach { (name, desc, emoji) ->
            item {
              ClawPanel {
                ClawDetailRow(
                  title = name,
                  subtitle = desc,
                  leading = { ClawTextBadge(text = emoji) },
                  trailing = {
                    ClawPrimaryButton(
                      text = "安装",
                      onClick = { /* TODO: 接入 API 后实现 */ },
                    )
                  },
                )
              }
            }
          }
          item {
            ClawPanel {
              Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Text(
                  text = "更多技能即将上线",
                  style = ClawTheme.type.body,
                  color = ClawTheme.colors.textMuted,
                )
                Text(
                  text = "请确保手机已连接到灵机网站以获取最新技能",
                  style = ClawTheme.type.caption,
                  color = ClawTheme.colors.textSubtle,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MarketplaceSkillCard(skill: MyAgentSkill) {
  ClawPanel {
    ClawDetailRow(
      title = skill.name,
      subtitle = "${skill.description ?: skill.slogan ?: ""} · ${skill.category ?: ""}${if (skill.price > 0) " · ${skill.price} 灵豆" else " · 免费"}",
      leading = {
        val emoji = skill.emoji ?: skill.name.firstOrNull()?.toString() ?: "S"
        ClawTextBadge(text = emoji)
      },
      trailing = {
        ClawPrimaryButton(
          text = if (skill.price > 0) "${skill.price} 灵豆" else "安装",
          onClick = { /* TODO: 接入 API 后实现安装逻辑 */ },
        )
      },
    )
  }
}

@Composable
private fun SkillsSummaryPanel(skills: List<GatewaySkillSummary>) {
  ClawListPanel(items = skills) { skill ->
    ClawDetailRow(
      title = skill.name,
      subtitle = skill.description ?: "",
      leading = {
        val emoji = skill.emoji ?: skill.name.firstOrNull()?.toString() ?: "S"
        ClawTextBadge(text = emoji)
      },
      trailing = {
        ClawStatusPill(
          text = if (skill.disabled) "已禁用" else "已安装",
          status = if (skill.disabled) ClawStatus.Neutral else ClawStatus.Success,
        )
      },
    )
  }
}