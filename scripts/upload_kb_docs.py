from __future__ import annotations

import argparse
import os

import requests

from paiagent_api import PaiAgentApiError, add_common_args, login, upload_document

# 模拟企业知识文档
DOCUMENTS = [
    {
        "fileName": "企业报销管理制度.txt",
        "content": """# 企业费用报销管理制度

## 第一章 总则

本制度适用于公司全体员工的日常费用报销管理。

## 第二章 报销流程

### 2.1 标准报销流程

1. 员工在发生费用后，需在30天内完成报销申请。
2. 填写报销单：登录公司OA系统，进入【财务管理】-【费用报销】模块，填写报销单。
3. 附上发票原件：所有报销必须提供正规增值税发票或收据原件，电子发票需打印并盖章。
4. 部门经理签字：报销单需由直属部门经理审核并签字确认。
5. 提交财务部：将签字后的报销单连同发票原件一并提交财务部。
6. 财务审核：财务人员对报销材料进行审核，不符合规定的将退回补充。
7. 打款：审核通过后，通常在5个工作日内将款项打入员工工资卡。

### 2.2 特殊报销说明

- 差旅费报销：需额外提供出差审批单、行程记录及相关凭证（机票、火车票、酒店发票等）。
- 招待费报销：需注明客户名称、招待事由，金额超过500元需总监级以上签字。
- 培训费报销：需提前获得HR部门审批，附上培训证明材料。

## 第三章 报销标准

| 类别 | 城市 | 住宿标准（元/天） | 餐补标准（元/天） |
|------|------|-----------------|-----------------|
| 出差 | 北上广深 | 500 | 100 |
| 出差 | 其他城市 | 350 | 80 |

## 第四章 注意事项

- 超过1000元的单笔报销需提前填写费用预申请。
- 禁止虚开发票、虚构报销事由等违规行为，一经发现按公司规定严肃处理。
- 所有发票抬头须为公司全称，否则无法报销。
"""
    },
    {
        "fileName": "员工年假管理规定.txt",
        "content": """# 员工年假管理规定

## 第一章 年假资格与天数

### 1.1 年假天数

员工在入职满一年后，即可享受带薪年假。具体天数如下：

| 工龄 | 年假天数 |
|------|---------|
| 满1年不满10年 | 5天 |
| 满10年不满20年 | 10天 |
| 满20年及以上 | 15天 |

### 1.2 当年度年假计算

新入职员工在第一年按比例享有年假，计算公式：
当年可用年假天数 = 年假总天数 × (本年度剩余日历天数 / 365)

## 第二章 年假申请流程

### 2.1 申请步骤

1. **提前申请**：年假需提前至少3个工作日在OA系统提交申请。节假日前后连休超过5天需提前7个工作日申请。
2. **在线提交**：登录OA系统，点击【我的假期】-【年假申请】，选择休假起止日期，填写请假事由。
3. **直属领导审批**：申请提交后，系统自动推送至直属领导进行审批，领导需在2个工作日内完成审批。
4. **HR备案**：领导审批通过后，系统自动同步至HR系统完成备案，员工可在OA系统查看年假余额变更。

### 2.2 申请注意事项

- 年假申请应尽量错开项目关键节点与团队高峰期。
- 公司业务繁忙时期（如年底结算季），年假申请可能被暂缓审批，请提前与领导沟通。
- 员工可以拆分使用年假，但单次年假不得少于半天。

## 第三章 年假结转与补偿

- 当年未使用的年假，可结转至下一年度，但最多结转5天。
- 因公司原因导致员工无法使用年假的，公司将按员工日工资200%进行补偿。
- 员工主动放弃年假的，不享受补偿。

## 第四章 特殊情况处理

- 员工在病假、产假、工伤假期间，年假继续保留。
- 员工在试用期间不享受年假，但转正后计入工龄。
"""
    },
    {
        "fileName": "IT技术支持手册-VPN与网络问题.txt",
        "content": """# IT技术支持手册 - VPN与远程访问

## 第一章 公司VPN使用说明

公司使用 GlobalProtect 企业VPN，员工在外出或居家办公时，需通过VPN访问公司内部系统和资源。

### 1.1 VPN客户端下载与初始安装

1. 打开浏览器，访问公司内网门户 https://vpn.company.com
2. 下载对应操作系统的 GlobalProtect 安装包（Windows/macOS/Linux）。
3. 按向导完成安装，安装完成后桌面会出现 GlobalProtect 图标。
4. 首次连接时，输入网关地址 `vpn.company.com`，使用公司域账号（工号）和密码登录。

## 第二章 VPN常见问题排查

### 2.1 VPN连接不上的排查步骤

**问题现象**：点击连接后，VPN一直显示"正在连接"或提示"连接失败"。

**解决步骤**：

**步骤1：检查网络连通性**
- 确认本机网络是否正常，尝试访问 https://www.baidu.com 确认外网通畅。
- 如果连接的是公共WiFi，部分公共网络会封锁VPN协议，请尝试切换至手机热点。

**步骤2：检查账号密码是否正确**
- 确认使用的是公司域账号（工号@company.com）和对应密码。
- 如果近期修改过域账号密码，VPN密码也会同步更新，请使用最新密码。
- 多次输入错误密码会导致账号被临时锁定，请联系IT部门解锁。

**步骤3：重置证书（Certificate Error / 证书过期）**
- 如果VPN提示"证书错误"或"证书过期"，需要重置证书。
- Windows系统：打开"运行"（Win+R），输入 `certmgr.msc`，在"个人"证书中找到并删除过期的 GlobalProtect 证书，然后重新连接VPN，系统会自动下载新证书。
- 或者直接联系IT部门协助远程操作，IT工程师可通过远程桌面帮你重置证书。
- IT部门联系方式：内线电话 8888 / 邮箱 it-support@company.com

**步骤4：重装VPN客户端**
- 如果以上步骤均无效，请卸载 GlobalProtect 后重新安装。
- 重新安装前，请先联系IT部门确认最新版本号，避免安装过期版本。

### 2.2 VPN连接成功但无法访问内部系统

- 检查是否访问的是内网地址（如 192.168.x.x 或以 .company.com 结尾的域名）。
- 尝试在VPN连接状态下 ping 内网服务器，确认路由是否正常。
- 联系IT部门确认你的账号是否有对应系统的访问权限。

### 2.3 VPN连接速度慢

- 优先选择"自动"模式，系统会自动选择延迟最低的VPN节点。
- 避免在VPN连接期间进行大文件上传/下载，这会影响整体速度。
- 下班非高峰时段（22:00后）访问速度通常更快。

## 第三章 联系IT部门

| 渠道 | 联系方式 | 服务时间 |
|------|---------|---------|
| 内线电话 | 8888 | 周一至周五 9:00-18:00 |
| 邮箱 | it-support@company.com | 24小时（紧急情况） |
| 企业微信 | 搜索"IT技术支持" | 工作时间实时响应 |
"""
    }
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Upload demo enterprise knowledge documents")
    add_common_args(parser)
    parser.add_argument(
        "--kb-id",
        type=int,
        default=int(os.getenv("PAIAGENT_KB_ID", "3")),
        help="Target knowledge base ID",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print documents without uploading")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    session = requests.Session()

    print("正在登录...")
    if not args.dry_run:
        login(
            session,
            base_url=args.base_url,
            username=args.username,
            password=args.password,
            timeout=args.timeout,
        )
    print(f"{'预览' if args.dry_run else '登录成功'}，目标知识库 ID: {args.kb_id}\n")

    for doc in DOCUMENTS:
        print(f"正在上传: {doc['fileName']} ({len(doc['content'])} 字)...")
        if args.dry_run:
            continue

        try:
            data = upload_document(
                session,
                base_url=args.base_url,
                kb_id=args.kb_id,
                file_name=doc["fileName"],
                content=doc["content"],
                timeout=args.timeout,
            )
            print(f"  [OK] 上传成功! 文档ID: {data.get('id')}, 切片数: {data.get('chunkCount', '处理中')}")
        except PaiAgentApiError as exc:
            print(f"  [FAIL] 上传失败: {exc}")

    print("\n所有文档处理完毕！")


if __name__ == "__main__":
    main()
