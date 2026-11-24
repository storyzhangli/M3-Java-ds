# D41 R03 — 第二厂商公开手册逐格对照（SICK CSS/CSX High Speed）

> R02 未解决项③（单一样本）扩展实证。样本：SICK AG 官方直链 Operating Instructions
> CSS/CSX High Speed（8028100.1JAW，86 页，SHA-256 `0048F317…8267F`，见 source-metadata.json）。
> 解析器：bc83b7e 代码（R02 算法）+ R02 只读探针 ProbeD41Manual.java，零改动、禁新增框架、禁 OCR 约束延续。
> 原页依据：Read 工具渲染本地 PDF page=78 / page=86 整页图（Image 对照，可复现）。

## 1. 总量口径

| 指标 | Danfoss MG38A141（R02） | SICK 8028100（R03） |
|---|---|---|
| 页数 / 字节 | 112 / 12 427 018 | 86 / 7 317 818 |
| 检出表数 | 411 | 157 |
| flags=[] 干净表 | （R02 未单列） | 30 / 157 (19.1%) |
| COLUMN_MISALIGNMENT | 212 | 82 |
| MERGED_CELL | 86 | 55 |
| LOW_CONFIDENCE | 47 | 49 |
| CONTINUATION_HEADER_SKIPPED | 22 | 19 |
| EXTRA_CELL | 29 | 3 |
| 符号字体缺 ToUnicode | ArialMT 子集（数字位） | SICKSymbols1 c49–c56（脚注编号 1–8） |

两厂商共性：**源层符号/子集字体缺 ToUnicode** 均出现，均不影响表格业务值列（SICK 缺失码对应脚注标记数字，非单元格内容）。

## 2. 逐格对照（人工核对 21 格，≥10 格要求满足）

### 2.1 page=86 各国服务地址三栏块表（16 格，值位全对）

| # | 源页原文 | 提取值（TSV 行） | 判定 |
|---|---|---|---|
| 1 | Australia Phone +61 (3) 9457 0600 | `Phone+61 (3) 9457 0600` | [OK]（标签与号码拼入同格，值完整） |
| 2 | Hungary Phone +36 1 371 2680 | `Phone+36 1 371 2680` | [OK] |
| 3 | Slovenia Phone +386 591 78849 | `Phone+386 591 78849` | [OK] |
| 4 | Hungary E-Mail ertekesites@sick.hu | `E-Mail ertekesites@sick.hu` | [OK] |
| 5 | Slovenia E-Mail office@sick.si | `E-Mail office@sick.si` | [OK] |
| 6 | Israel E-Mail info@sick-sensors.com | `E-Mail info@sick-sensors.com` | [OK] |
| 7 | India Phone +91-22-6119 8900 | `Phone+91-22-6119 8900` | [OK] |
| 8 | Italy Phone +39 02 27 43 41 | `Phone+39 02 27 43 41` | [OK] |
| 9 | Spain Phone +34 93 480 31 00 | `Phone+34 93 480 31 00` | [OK] |
| 10 | Canada Phone +1 905.771.1444 | `Phone+1 905.771.1444` | [OK] |
| 11 | Japan E-Mail support@sick.jp | `E-Mail support@sick.jp` | [OK] |
| 12 | Sweden E-Mail info@sick.se | `E-Mail info@sick.se` | [OK] |
| 13 | South Korea Phone +82 2 786 6321/4 | `Phone+82 2 786 6321/4` | [OK] |
| 14 | Belgium Phone +32 (0) 2 466 55 66 | `Phone +32 (0) 2 466 55 66`（原文带空格版） | [OK] |
| 15 | Austria Phone +43 (0) 2236 62288-0 | `Phone+43 (0) 2236 62288-0` | [OK] |
| 16 | Brazil E-Mail comercial@sick.com.br | `E-Mail comercial@sick.com.br` | [OK] |

结构偏差（零静默、有旗标）：原文为"国家/电话/邮箱 × 45 国"三栏记录块，提取按 3 列网格切分，
国家名混入相邻记录位（ROW2 c1=`India`、ROW3 c1=`Phone+91…` 等）。表级 flags=[COLUMN_MISALIGNMENT] 已标注；
组归属错乱但 16/16 格值无丢失。与 R02 已声明的"双栏/多栏正文误判候选表"能力边界同类（D42+）。

### 2.2 page=78 表 30 Status 寄存器表（5 格值对 + 3 项真实缺陷）

| # | 源页原文 | 提取值 | 判定 |
|---|---|---|---|
| 17 | 4097 / Current Temperature / 1 / INT16 / Internal device temperature in °C | HEADER `4097,Current Temperature,1,INT16,Internal device temperature in °C` | [OK] 5 格全对；缺陷：首行数据升格为 header（无表头样式判据） |
| 18 | 4098 / Max. Temperature all time / 1 / INT16 | ROW0 前 4 格 | [OK] |
| 19 | 4098 描述列两行 "Maximum internal device temperature / since production of the sensor in °C" | ROW0 c4=`Maximum internal device temperature` | [DIFF] **第二行缺失**（行内多行单元续行未归并） |

覆盖缺陷（新发现，见 §3）：表 30 数据 11 行（4096/4097/4098/4099/4100/4102/4103/4104/4106/4107…），
提取仅 2 行（4097、4098），其余 **9 行漏检**，4096 的多行描述（1=Device OK … 4=Error）未成任何行。

### 2.3 page=79/80 寄存器小表（2 格结构偏差）

| # | 源页原文 | 提取值 | 判定 |
|---|---|---|---|
| 20 | 4145 / Quality of Run / 1 / UINT16 / Process quality in percent（5 列） | `UINT16Process quality in percent`（4 列） | [DIFF] 类型列与描述列粘连（无 x 间隔） |
| 21 | 144 / Modbus RTU Slave Address / 1 / UINT16 / 1 - 247 (factory setting: 10)（5 列） | `UINT161 - 247 (factory setting: 10)`（4 列） | [DIFF] 同 20；且连续大表断裂为 2 行/片 × 多片 |

## 3. 新发现清单（登记为 R03 输出，不属 R02 引入、不在本轮修复）

1. **大表行覆盖漏检 / 断裂**（page 78 表 30 仅 2/11 行；page 79/80 连续表断为 2 行微表）：
   [confirmed] 现象与计数；根因**假设**为多行行高（wrap 单元格）下逐行 y 间距聚类断链，未做代码级定位
   （本轮范围外，D42 表格域能力演进项）。定性依据：R02 改动面为桶内列归属/表头占位（assignColumns/assignHeader），
   表区域/行识别层逻辑与 R01 一致，夹具 HTTP 复跑（§4）实证无回归。
2. **粘连列**：类型列与描述列 x 间距低于分桶分辨率时合并为一格（UINT16+描述），零旗标——
   R02 EXTRA_CELL 机制只处理"超列"，不处理"列粘连"；属能力边界，登记。
3. **无表头样式表中首行数据升格 header**：语义瑕疵（header/row 角色错位，值不丢），登记。
4. **多栏排版组归属错乱**：与 R02 报告既有边界一致（COLUMN_MISALIGNMENT 旗标可见，零静默）。

## 4. 与夹具对账的边界声明

本节属**真实手册交叉验证**；受控夹具 113 格对账（104+9）实证见 `..\r01-exercise-replay\s2-reconcile-report.json`
（§4 of R03 README）。两套证据互不冒充：夹具 PASS 不证明真实手册覆盖，手册对照不替代夹具基线对账。

## 5. 复现命令

```powershell
$root='E:\AI\project\AI-Learning-two-lp\M3-Java-ds'
$cp = "$root\D41\D41_TASIIEM\target\classes;" + (Get-Content "$root\temp_docs\D41\manuals\r02-cp.txt" -Raw).Trim()
& 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' '-Dstdout.encoding=UTF-8' -cp $cp `
  "$root\docs\evidence\D41\R02\scripts\ProbeD41Manual.java" `
  "$root\temp_docs\D41\manuals\sick-css-csx-highspeed-en.pdf" `
  "$root\docs\evidence\D41\R03\public-manual-second\sick"
```

（全量 TSV：sick/manual-tables.tsv，50 088 B；探针 exit=0；WARN 为 SICKSymbols1 ToUnicode 缺失，非解析失败。）
