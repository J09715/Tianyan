export interface DiffRow {
  kind: "add" | "del" | "same";
  text: string;
}

const MAX_DIFF_LINES = 400;

/**
 * 行级 LCS diff。文件编辑块通常几十行，O(n·m) 足够；
 * 任一侧超过 400 行时退化为全删全加并标注省略，避免长文件卡顿。
 */
export function diffLines(before: string, after: string): DiffRow[] {
  const left = before.split("\n");
  const right = after.split("\n");
  if (left.length > MAX_DIFF_LINES || right.length > MAX_DIFF_LINES) {
    const rows: DiffRow[] = [];
    for (const text of left.slice(0, MAX_DIFF_LINES)) rows.push({ kind: "del", text });
    for (const text of right.slice(0, MAX_DIFF_LINES)) rows.push({ kind: "add", text });
    rows.push({ kind: "same", text: `… 内容过长，仅显示前 ${MAX_DIFF_LINES} 行 …` });
    return rows;
  }

  const width = right.length;
  // lcs[i][j] = left[i..] 与 right[j..] 的最长公共子序列长度（滚动压平为一维）。
  const lcs = new Uint32Array((left.length + 1) * (width + 1));
  for (let i = left.length - 1; i >= 0; i -= 1) {
    for (let j = width - 1; j >= 0; j -= 1) {
      lcs[i * (width + 1) + j] = left[i] === right[j]
        ? lcs[(i + 1) * (width + 1) + j + 1] + 1
        : Math.max(lcs[(i + 1) * (width + 1) + j], lcs[i * (width + 1) + j + 1]);
    }
  }

  const result: DiffRow[] = [];
  let i = 0;
  let j = 0;
  while (i < left.length && j < width) {
    if (left[i] === right[j]) {
      result.push({ kind: "same", text: left[i] });
      i += 1;
      j += 1;
    } else if (lcs[(i + 1) * (width + 1) + j] >= lcs[i * (width + 1) + j + 1]) {
      result.push({ kind: "del", text: left[i] });
      i += 1;
    } else {
      result.push({ kind: "add", text: right[j] });
      j += 1;
    }
  }
  while (i < left.length) {
    result.push({ kind: "del", text: left[i] });
    i += 1;
  }
  while (j < width) {
    result.push({ kind: "add", text: right[j] });
    j += 1;
  }
  return result;
}
