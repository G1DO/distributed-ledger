# Decision identifiers and historical crosswalk

Repository decision paths are canonical for implementation. Earlier Notion records assigned
some of the same numbers to different subjects; do not resolve those references by number
alone or rename old records and break their links.

| Subject | Repository | Historical Notion record |
| --- | --- | --- |
| Single repository | [01](DEC-LEDGER-01-merge.md) | DEC-LEDGER-01 merge |
| Java 25 baseline | [Build policy](../development/testing.md) | [DEC-LEDGER-02 Java 25](https://app.notion.com/p/3d60a821b3cc8172b35fc1fefb835962) |
| Spring Boot 4 | [02](DEC-LEDGER-02-boot4x.md) | Not the Java 25 decision |
| Six-table schema | [03](DEC-LEDGER-03-schema.md) | Not the concurrency investigation |
| Concurrency | [04](DEC-LEDGER-04-concurrency.md) | [DEC-LEDGER-03 investigation](https://app.notion.com/p/3d60a821b3cc816d9af3dfbbf7b8898d) |
| Operation-first claim | [05](DEC-LEDGER-05-operation-claim.md) | Not the blueprint's historical invariant-scope reference |
| PITR and reconciliation | Planned, no implementation ADR yet | [DEC-LEDGER-04 PITR](https://app.notion.com/p/3d60a821b3cc81ca9ffcee867d582a6e) |

The [approved delivery plan](https://app.notion.com/p/3d90a821b3cc81b4a5a4f62fa6dd30a3)
reconciles the old public-API and release-scope conflicts. Repository docs describe implemented
behavior; the plan and outcomes distinguish planned work. Invariant I6 still means tenant
isolation and remains unimplemented until O3.
