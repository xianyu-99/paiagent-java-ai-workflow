from __future__ import annotations

import argparse
import os

import requests

from paiagent_api import PaiAgentApiError, add_common_args, login, upload_document

# 之前已上传的核心文档
# 1. 企业报销管理制度
# 2. 员工年假管理规定
# 3. IT技术支持手册-VPN与网络问题

# 增加大量的“干扰”文档 (Noise Documents) 来增加检索难度
NOISE_DOCUMENTS = [
    {
        "fileName": "员工着装规范.txt",
        "content": "为了展现公司专业形象，周一至周四员工需穿着商务休闲装，不得穿拖鞋、短裤。周五可穿着休闲装（如牛仔裤、T恤）。重要客户会议必须着正装。"
    },
    {
        "fileName": "办公室消防安全管理规定.txt",
        "content": "办公区域严禁吸烟、存放易燃易爆物品。最后离开办公室的员工必须关闭所有电器电源。每月将进行一次消防器材检查，每半年组织一次消防演习。"
    },
    {
        "fileName": "食堂就餐管理办法.txt",
        "content": "公司食堂供应早、中、晚三餐。午餐时间为11:30-13:00。员工需刷工牌就餐，严禁浪费粮食。外来访客就餐需由接待部门提前向行政部申请访客餐券。"
    },
    {
        "fileName": "公司车辆使用管理规定.txt",
        "content": "公司公车仅限用于公务活动，如拜访客户、接送重要宾客等。用车需提前在OA系统【行政服务】中提交申请。驾驶员需持有有效驾驶证，严禁酒后驾车、公车私用。"
    },
    {
        "fileName": "员工培训与发展指南.txt",
        "content": "公司鼓励员工持续学习。转正员工每年享有2000元的外部培训基金。内部培训平台每月更新课程，员工需在年底前完成至少40小时的必修课学习。"
    },
    {
        "fileName": "会议室预订及使用规范.txt",
        "content": "会议室通过OA系统预订，遵循先到先得原则。使用完毕后需将白板擦拭干净，座椅归位，关闭投影仪和空调。连续预订超过4小时需说明特殊事由。"
    },
    {
        "fileName": "办公用品领用制度.txt",
        "content": "常规办公用品（笔、本、文件夹等）每月5号前在行政部统一登记领用。特殊或高价值办公用品（如移动硬盘、人体工学椅）需部门负责人审批后采购。"
    },
    {
        "fileName": "知识产权保护声明.txt",
        "content": "员工在职期间产生的代码、文档、设计图纸等所有工作成果，其知识产权均归公司所有。严禁通过个人邮箱、U盘等方式将涉密资料泄露给第三方。"
    },
    {
        "fileName": "疫情常态化防控指南.txt",
        "content": "进入办公楼需测量体温。办公区域每天进行两次消毒。如有发热、咳嗽等症状，请及时就医并居家办公，向HR部门报备健康状况。"
    },
    {
        "fileName": "新员工入职指引.txt",
        "content": "欢迎加入公司！入职第一天需前往HR部门办理入职手续，领取电脑、工牌。IT部门将协助配置系统账号。请在一周内完成新员工线上必修培训。"
    },
    {
        "fileName": "离职交接管理办法.txt",
        "content": "员工提出离职需提前30天提交申请。离职交接包括工作任务交接、设备归还、系统权限注销等。所有交接清单需经直属领导、IT部、行政部、HR部确认。"
    },
    {
        "fileName": "节假日福利发放标准.txt",
        "content": "端午节、中秋节公司将发放价值约300元的节日礼盒。春节将发放年货及红包。员工生日当月可领取生日蛋糕券。三八妇女节女员工享有半天带薪假及专属礼品。"
    },
    {
        "fileName": "Git代码仓库使用规范.txt",
        "content": "所有代码提交必须遵循规范的Commit Message格式：[类型] 描述，如 [feat] 增加登录功能。主分支(main/master)禁止直接push，必须通过Pull Request并经过Code Review方可合并。"
    },
    {
        "fileName": "云服务器安全基线要求.txt",
        "content": "禁止开放数据库公网访问端口（如3306、6379）。SSH登录必须使用密钥认证，禁止密码登录。定期检查系统日志，及时修补高危漏洞。"
    },
    {
        "fileName": "差旅安全提示.txt",
        "content": "出差期间请注意人身及财产安全。避免前往存在安全风险的地区。妥善保管个人证件和贵重物品。遇到紧急情况，请优先确保人身安全并及时联系主管和当地警方。"
    }
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Upload noise documents for RAG retrieval testing")
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

    for doc in NOISE_DOCUMENTS:
        print(f"正在上传干扰文档: {doc['fileName']}...")
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
            print(f"  [OK] 上传成功! 文档ID: {data.get('id')}")
        except PaiAgentApiError as exc:
            print(f"  [FAIL] 上传失败: {exc}")

    print("\n所有干扰文档处理完毕！现在知识库中包含更多噪音数据，可以更好地测试检索精度。")


if __name__ == "__main__":
    main()
