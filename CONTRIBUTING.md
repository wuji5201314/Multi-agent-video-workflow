# 提交规范

这个仓库同时放三部分代码，所以提交粒度比普通项目更重要：**混在一起的提交回滚不掉单独一面。**

## 一、提交单元怎么划

判断标准只有一条：

> **单独 revert 这个提交，项目还能跑吗？**

- 能 → 独立提交
- 不能 → 和它依赖的改动合成一个提交

## 二、前缀与作用域

| 前缀 | 改动范围 | 是否可以单独回滚 |
|---|---|---|
| `feat(python)` | `backend-python/` | 可以 |
| `feat(web)` | `backend-java/frontend/` | 可以 |
| `feat(java)` | `backend-java/aimedia-module/` | 可以 |
| `feat(python,web)` | 前后端契约变更 | **必须整体回滚** |
| `fix(...)` `refactor(...)` `perf(...)` | 同上规则 | 同上 |
| `docs(...)` | `docs/`、README | 可以 |
| `chore(repo)` | `.gitignore`、启动脚本、CI | 可以 |
| `style(web)` | 纯样式，无逻辑 | 可以 |

## 三、必须合并成一个提交的情况

1. 新增 / 删除 / 重命名 API 路由
2. 修改接口的字段名、字段结构、返回类型
3. 前端依赖的后端行为发生变化（例如错误码含义变了）
4. 数据库 schema 变更 + 依赖它的读写代码

反例：把「Python 新增 `/api/images/analyze`」和「前端调用这个接口」拆成两个提交。单独回滚后端 → 前端 404；单独回滚前端 → 后端多一个没人调的路由。两种都不可运行。

## 四、提交信息格式

```
<type>(<scope>): <一句话说清做了什么>

可选正文：为什么这么做、影响了什么、有什么已知限制。
```

示例：

```
feat(python): 图片生成支持 steps 与 cfg_scale 参数

NVIDIA 的 flux 接口实测接受这两个字段，实测 8 步即可出可用图，
标准档位定为 20 步。渲染侧无需改动。
```

## 五、提交前自检

```powershell
git status --short          # 看清改了哪些目录
git diff --cached --stat    # 确认暂存范围
```

按目录分段提交：

```powershell
git add backend-python/
git commit -m "feat(python): ..."

git add backend-java/frontend/
git commit -m "feat(web): ..."

git add .gitignore README.md docs/
git commit -m "chore(repo): ..."
```

## 六、绝对不要提交的东西

见 `.gitignore`。最要紧的三条：

- `backend-python/.env` —— 真实 API key，一旦进历史就只能改 key 并重写历史
- `backend-python/output/` —— 成片、素材、数据库，动辄几十 MB
- `backend-python/.venv/` —— 几万个文件

## 七、分支

| 分支 | 用途 |
|---|---|
| `main` | 任何时候 clone 下来都能跑通 `start.cmd` |
| `feature/<短名>` | 新功能 |
| `fix/<短名>` | 修问题 |

个人项目直接在 `main` 上小步提交也可以，但**提交前跑一遍** `python scripts/smoke_api.py` 和两端启动，保证 main 永远可用。

## 八、回滚

```powershell
# 整次提交回滚（会生成一个新提交，历史保留）
git revert <sha>

# 只恢复某个目录到某次提交的状态（这是覆盖，不是回滚，需自己再提交）
git checkout <sha> -- backend-python/

# 撤销还没提交的本地改动（危险，改动会丢失）
git checkout -- <path>
```

按本规范提交后，「只回滚后端、保留前端」这类需求用 `git revert` 一条命令就能完成。
