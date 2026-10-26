# QWEN.md

Qwen Code reads this file at the start of every session. The instructions are
in CLAUDE.md, imported below. The rule files and skills it names are reachable
for Qwen Code through `.qwen/rules` and `.qwen/skills`, which are symlinks to
`.claude/rules` and `.claude/skills`: a rule loads when a file matching its
`paths:` is read or edited, a skill when its description matches the task or
`/<name>` is typed. Change the files under `.claude/`, never these.

On Windows, Git checks symlinks out as plain text files unless `core.symlinks`
is true (Developer Mode, or `git clone -c core.symlinks=true`). Without it,
point Qwen Code at the skills with its `skills.directories` setting
(`.claude/skills`), and read the rule files under `.claude/rules/` yourself
for the modules you touch.

@CLAUDE.md
