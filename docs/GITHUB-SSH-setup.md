# GitHub SSH 配置与推送步骤（Windows）

> 说明：仓库当前 remote 是 HTTPS，本机尚未配置 git 身份与 SSH 密钥（2026-09 检查结果）。
> 配置后即可用 SSH 推送。以下命令在 **PowerShell** 或 **CMD** 执行。

## 1. 配置 git 身份（提交作者）

```bat
git config --global user.name  "你的名字或拼音"        REM 例：yjc
git config --global user.email "你的GitHub邮箱"
```

查看：`git config --global --list`

## 2. 生成 SSH 密钥（ed25519）

```bat
ssh-keygen -t ed25519 -C "你的GitHub邮箱"
```

- 默认保存路径：`C:\Users\yjc\.ssh\id_ed25519`
- 提示 passphrase 可回车留空（本机常用建议留空即可）
- 将生成两个文件：`id_ed25519`（私钥，勿外传）、`id_ed25519.pub`（公钥，可分享）

## 3. 复制公钥内容

```bat
type %USERPROFILE%\.ssh\id_ed25519.pub
```

复制整行输出（以 `ssh-ed25519 AAAA...` 开头）。

## 4. 添加到 GitHub

1. 浏览器登录 GitHub → 右上角头像 → **Settings**
2. 左侧 **SSH and GPG keys** → **New SSH key**
3. Title 随便填（如 `yjc-PC`），Key Type 选 **Authentication Key**
4. 把上一步复制的公钥粘贴到 Key 框 → **Add SSH key**

## 5. 切换 remote 为 SSH

```bat
cd /d C:\Users\yjc\Desktop\project\project-manager
git remote set-url origin git@github.com:ggbonds6/project-manager.git
git remote -v        REM 应显示 git@github.com:...
```

## 6. 验证连通

```bat
ssh -T git@github.com
```

预期看到：`Hi ggbonds6! You've successfully authenticated, but GitHub does not provide shell access.`

> 如果第 6 步连不上（提示 22 端口超时，常见于公司/校园网），改用 SSH-over-443：
> 在 `C:\Users\yjc\.ssh\config`（无则新建）写入：
> ```
> Host github.com
>   HostName ssh.github.com
>   Port 443
>   User git
> ```

## 7. 推送（示例）

```bat
cd /d C:\Users\yjc\Desktop\project\project-manager
git status                     REM 先看改动
git add -A
git commit -m "feat: 说明你的改动"
git push origin main
```

## 8. 常见问题

| 现象 | 处理 |
| --- | --- |
| 提交报 author identity unknown | 未执行第 1 步 |
| `Permission denied (publickey)` | 公钥未加/加错 GitHub；确认第 3、4 步 |
| 22 端口连不上 | 用第 6 步的 443 方案 |
| push 被拒 non-fast-forward | `git pull --rebase origin main` 后再 push |
| 想保留 HTTPS + 个人令牌推送 | 也可：remote 改回 https，推送时账号输 token |
