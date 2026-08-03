# Mock Notes CLI

A small command-line notebook used as the test target for the J-Coder context compression evaluation.

## Subcommands

- `add <title> <body>` — append a new note.
- `list` — print all notes in insertion order.
- `get <id>` — print a single note by id.
- `delete <id>` — remove a note by id.
- `count` — print the number of notes.
- `search <keyword>` — search notes by keyword (case-insensitive, matches title or body).

## Build & Test

- Build: `mvn -q -DskipTests package`
- Test: `mvn -q test`
- Run: `mvn -q exec:java -Dexec.mainClass=com.example.notes.Main -Dexec.args="list"`

## Notes

- Notes are kept in-memory only; the process restart wipes everything.
- IDs are sequential integers assigned at insertion time.