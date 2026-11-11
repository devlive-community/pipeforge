# Governance

## Roles

| Role | Responsibilities | How to become one |
|---|---|---|
| Contributor | Opens issues and pull requests, writes documentation and plugins | Anyone |
| Reviewer | Reviews code for specific modules | Sustained, high-quality contributions to a module; nominated by a maintainer |
| Maintainer | Merges code, publishes releases, decides the roadmap, handles security reports | Nominated by an existing maintainer and approved by a vote of the maintainers |

The current maintainers and the modules they own are listed in [MAINTAINERS.md](MAINTAINERS.md);
[`.github/CODEOWNERS`](.github/CODEOWNERS) mirrors that list.

## Decision making

- Day-to-day decisions are made by the maintainers of the affected module.
- Changes to `pipeforge-api`, the wire protocol, the checkpoint format, the architecture or the
  key design decisions are proposed as a **design proposal** (an RFC issue or discussion). A
  proposal stays open for public discussion for at least 7 days and is accepted when the
  maintainers reach consensus.
- When consensus cannot be reached, the maintainers decide by simple majority vote.

### Design proposal template

1. Background
2. Goals and non-goals
3. Proposed design
4. Compatibility
5. Alternatives considered
6. Test plan
7. Documentation plan

## Releases

| Kind | Cadence | Content |
|---|---|---|
| Minor (x.Y.0) | about every 3 months | new features and plugins |
| Patch (x.y.Z) | as needed, usually every 2–4 weeks | bug and security fixes only |
| Major (X.0.0) | as needed | breaking changes, announced one minor version ahead |

The two most recent minor versions receive bug fixes; the four most recent receive security
fixes. Plugins are versioned and released independently.

## Changes to this document

Changes to this governance document follow the design proposal process above.
