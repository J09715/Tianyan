#!/usr/bin/env python3
"""
把一批 Kotlin UI 文件的间距/圆角硬编码迁移到设计令牌。

为什么要有这个脚本：全仓 752 个 .kt 文件里有 3644 处硬编码 dp，同一个视觉意图
（元素内间距）在不同文件里落在 8/10/12 三个值上。手工一个一个改，既慢又容易漏。
迁移规则是机械的：把某个 dp 数值映射到某个令牌档位，然后批量替换。

安全约束：
- 只处理**间距类**写法：Arrangement.spacedBy / .padding( / PaddingValues( / Spacer(。
  尺寸类（size/height/width/maxWidth）保持裸 dp——它们是具体造型不是间距意图。
- **只迁移落在档位表里的数值**。0.dp（清零）、104.dp（底栏避让高度）这类
  有具体语义的数字原样保留，不强行塞进档位。
- 替换后逐文件校验括号平衡，任一不过就整批中止。
- 需要 Radius 令牌但文件里仍有 RoundedCornerShape(其它形状)时，保留其 import。

用法：token_migrate.py <文件...>
"""
import re
import sys

# 间距档位：dp 数值 → 令牌表达式。只列有意收口的中间值，
# 不在表内的数值（0 / 104 / 260 …）原样保留。
SPACING = {
    1: 'Spacing.xs', 2: 'Spacing.xs', 3: 'Spacing.xs', 4: 'Spacing.xs',
    5: 'Spacing.xs', 6: 'Spacing.sm', 7: 'Spacing.sm', 8: 'Spacing.sm',
    9: 'Spacing.sm', 10: 'Spacing.sm', 11: 'Spacing.sm', 12: 'Spacing.md',
    13: 'Spacing.md', 14: 'Spacing.md', 15: 'Spacing.md', 16: 'Spacing.lg',
    18: 'Spacing.lg', 20: 'Spacing.xl', 24: 'Spacing.xl', 32: 'Spacing.xxl',
}

# 圆角档位：dp 数值 → 令牌表达式
RADIUS = {
    1: 'Radius.xsShape', 2: 'Radius.xsShape', 3: 'Radius.xsShape',
    4: 'Radius.xsShape', 5: 'Radius.xsShape', 6: 'Radius.xsShape',
    7: 'Radius.xsShape', 8: 'Radius.xsShape',
    10: 'Radius.smShape', 12: 'Radius.smShape', 13: 'Radius.smShape',
    14: 'Radius.mdShape', 16: 'Radius.mdShape',
    18: 'Radius.lgShape', 20: 'Radius.xlShape', 22: 'Radius.lgShape',
    24: 'Radius.lgShape', 32: 'Radius.xlShape',
}

# dp 字面量：Kotlin 写作 8.dp（数字 + 点 + dp），不是 8dp
DP = r'(\d+(?:\.\d+)?)\.dp'
DP_RE = re.compile(DP)

# 胶囊圆角：999dp 及以上
PILL_RE = re.compile(r'RoundedCornerShape\((\d+(?:\.\d+)?)\.dp\)')
PLAIN_RCS_RE = re.compile(r'RoundedCornerShape\((\d+(?:\.\d+)?)\.dp\)')


def _tok_num(raw: str, table: dict) -> str:
    """把 dp 数值映射成令牌；不在表内则原样返回该 dp 字面量。"""
    v = float(raw)
    key = int(v) if v == int(v) else None
    if key not in table:
        return f'{raw}.dp'
    return table[key]


def _map_params(inner: str, table: dict) -> str:
    """把一段参数里的所有 dp 字面量按表映射。"""
    return DP_RE.sub(lambda m: _tok_num(m.group(1), table), inner)


def migrate_spacing(s: str) -> str:
    """替换 Arrangement.spacedBy / .padding / PaddingValues / Spacer 里的裸 dp。"""
    # Arrangement.spacedBy(N.dp) / spacedBy(N.dp, alignment)
    # 只替换 dp 字面量本身，保留后面可能存在的 alignment 实参。
    s = re.sub(
        r'Arrangement\.spacedBy\((\d+(?:\.\d+)?)\.dp',
        lambda m: f'Arrangement.spacedBy({_tok_num(m.group(1), SPACING)}',
        s,
    )
    # PaddingValues(...) / .padding(...)
    s = re.sub(
        r'PaddingValues\(([^()]*)\)',
        lambda m: f'PaddingValues({_map_params(m.group(1), SPACING)})',
        s,
    )
    s = re.sub(
        r'\.padding\(([^()]*)\)',
        lambda m: f'.padding({_map_params(m.group(1), SPACING)})',
        s,
    )
    # Spacer(Modifier.width(N.dp)) / height
    s = re.sub(
        r'Spacer\(Modifier\.(width|height)\((\d+(?:\.\d+)?)\.dp\)\)',
        lambda m: (
            f'Spacer(Modifier.{m.group(1)}('
            f'{_tok_num(m.group(2), SPACING)}))'
        ),
        s,
    )
    return s


def migrate_radius(s: str) -> str:
    """把 RoundedCornerShape(N.dp) 换成 Radius.*Shape；胶囊换 percent = 50。"""
    def one(m: re.Match) -> str:
        v = float(m.group(1))
        if v >= 100:
            return 'RoundedCornerShape(percent = 50)'
        key = int(v) if v == int(v) else None
        if key not in RADIUS:
            return m.group(0)
        return RADIUS[key]
    return PLAIN_RCS_RE.sub(one, s)


def ensure_import(s: str, token: str) -> str:
    imp = f'import top.tianyan.app.ui.theme.{token}\n'
    if imp in s:
        return s
    m = re.search(r'import top\.tianyan\.app\.ui\.theme\.[A-Za-z]+\n', s)
    if m:
        return s[:m.end()] + imp + s[m.end():]
    m = re.search(r'import top\.tianyan\.app\.ui\.components\.[A-Za-z]+\n', s)
    if m:
        return s[:m.end()] + imp + s[m.end():]
    m = re.search(r'import top\.tianyan\.[A-Za-z.]+\n', s)
    if m:
        return s[:m.end()] + imp + s[m.end():]
    return s


def main(paths: list[str]) -> int:
    changed = []
    for p in paths:
        orig = open(p, encoding='utf-8').read()
        s = migrate_spacing(orig)
        s = migrate_radius(s)

        if s == orig:
            continue

        if 'Spacing.' in s:
            s = ensure_import(s, 'Spacing')
        if 'Radius.' in s:
            s = ensure_import(s, 'Radius')
        if 'RoundedCornerShape(' not in s:
            s = s.replace('import androidx.compose.foundation.shape.RoundedCornerShape\n', '')

        # 括号平衡校验：迁移前后的差值必须一致
        for a, b in (('{', '}'), ('(', ')')):
            if s.count(a) - s.count(b) != orig.count(a) - orig.count(b):
                print(f'ABORT 括号失衡: {p}', file=sys.stderr)
                return 1
        open(p, 'w', encoding='utf-8').write(s)
        changed.append(p)
    for p in changed:
        print(f'updated {p}')
    print(f'--- {len(changed)} 个文件 ---')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
