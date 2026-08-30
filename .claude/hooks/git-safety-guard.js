#!/usr/bin/env node
// PreToolUse guard for Bash: catches known concurrent-Claude-session git accidents
// documented in memory project_concurrent_sessions.md. This repo often has more than
// one Claude session working in the same checkout at once.
//
// BLOCK: `git add -A` / `git add .` / `git commit --amend` without a pathspec —
//        these silently sweep another session's uncommitted work into this commit.
// WARN:  destructive or branch-switching git commands — remind to check `git status`
//        and stash (not discard) unrelated changes before proceeding.

let input = '';
process.stdin.on('data', (chunk) => { input += chunk; });
process.stdin.on('end', () => {
  try {
    run(JSON.parse(input));
  } catch {
    // Fail open: never block on a parsing bug.
    process.exit(0);
  }
});

function run(payload) {
  const command = payload && payload.tool_input && payload.tool_input.command;
  if (typeof command !== 'string' || !command.trim()) return allow();

  // Split on shell chaining operators so each git invocation in a compound
  // command (a && b, a; b, a | b) is inspected independently.
  const segments = command.split(/&&|\|\||[;|]/).map((s) => s.trim()).filter(Boolean);

  const blocks = [];
  const warns = [];

  for (const segment of segments) {
    const tokens = segment.split(/\s+/);
    if (tokens[0] !== 'git') continue;
    const sub = tokens[1];

    if (sub === 'add' && (tokens.includes('-A') || tokens.includes('--all') ||
        tokens.slice(2).includes('.'))) {
      blocks.push(`\`${segment}\` — git add -A/--all/. can sweep another session's uncommitted work into this commit`);
    }

    if (sub === 'commit' && tokens.includes('--amend') && !tokens.includes('--')) {
      blocks.push(`\`${segment}\` — git commit --amend without a pathspec re-commits the whole index, including anything another session staged`);
    }

    const isForceClean = sub === 'clean' &&
      tokens.slice(2).some((t) => t === '--force' || /^-\w*f\w*$/.test(t));
    const isHardReset = sub === 'reset' && tokens.includes('--hard');
    const isBranchDelete = sub === 'branch' && (tokens.includes('-D') || tokens.includes('--delete'));
    const isSwitchLike = sub === 'checkout' || sub === 'switch' || sub === 'rebase';

    if (isForceClean || isHardReset || isBranchDelete || isSwitchLike) {
      warns.push(`\`${segment}\``);
    }
  }

  if (blocks.length > 0) {
    return deny(
      blocks.join('; ') +
      '. Stage/commit specific files by name instead (git add <file> ..., git commit -- <files>).'
    );
  }

  if (warns.length > 0) {
    return allow(
      `이 저장소는 동시에 여러 Claude 세션이 같은 체크아웃에서 작업할 수 있습니다 (${warns.join(', ')}). ` +
      '실행 전에 git status로 다른 세션의 미커밋 변경이 있는지 먼저 확인하고, 있다면 버리지 말고 stash로 보존하세요.'
    );
  }

  return allow();
}

function allow(additionalContext) {
  const out = {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'allow',
    },
  };
  if (additionalContext) {
    out.hookSpecificOutput.additionalContext = additionalContext;
    out.systemMessage = additionalContext;
  }
  console.log(JSON.stringify(out));
  process.exit(0);
}

function deny(reason) {
  console.log(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason: reason,
    },
  }));
  process.exit(0);
}
