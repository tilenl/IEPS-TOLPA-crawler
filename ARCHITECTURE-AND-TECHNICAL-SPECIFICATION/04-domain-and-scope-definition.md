# Domain And Scope Definition

## Primary Crawl Domain

- primary domain: `github.com`
- target content: repositories related to image segmentation tasks

## Seeds

Starting frontier URLs are written in full and inserted as initial `FRONTIER` candidates when bootstrap runs on an empty frontier.

- topic pages:
  - `https://github.com/topics/image-segmentation`
  - `https://github.com/topics/semantic-segmentation`
  - `https://github.com/topics/medical-image-segmentation`
- canonical repositories:
  - `https://github.com/facebookresearch/segment-anything`
  - `https://github.com/open-mmlab/mmsegmentation`
  - `https://github.com/jfzhang95/pytorch-deeplab-xception`
  - `https://github.com/milesial/Pytorch-UNet`
- low-priority generic model hubs (for cross-link expansion):
  - `https://github.com/TexasInstruments/edgeai-modelzoo`
  - `https://github.com/onnx/models`

Notes:
- `edgeai-modelzoo` and `onnx/models` are intentionally lower-priority seeds because they are broad model hubs and will generate more non-segmentation candidates.
- all discovered URLs still pass canonicalization, robots policy, and relevance scoring before final frontier insertion.

## Scope Rules

- crawl repository landing pages and topic pages;
- follow extracted links if relevant;
- do not rely on disallowed paths from robots;
- do not clone repos or traverse full repo trees.

Hard budget guardrails (normative):
- maximum stored pages per run: `5000` (`crawler.budget.maxTotalPages`);
- ingestion must stop inserting new frontier rows when global cap is reached;
- per-domain and depth guardrails (`crawler.budget.maxPerDomainPages`, `crawler.budget.maxDepth`) limit expansion breadth;
- on budget rejection, link relation MAY still be persisted when target already exists, but no new page row is created.

## Relevance Domain

- primary segmentation keywords (high priority);
- secondary adjacent CV terms (lower priority fallback);
- score computed at ingestion, before frontier insert.

Reference keyword examples for scoring dictionaries:
- primary: `image segmentation`, `semantic segmentation`, `instance segmentation`, `mask`, `u-net`, `deeplab`, `segment anything`.
- secondary fallback: `object detection`, `image recognition`, `feature extraction`.

Scoring intent:
- URLs with primary-term signals should dominate frontier priority;
- secondary-term-only URLs stay crawlable but are deprioritized.

## Out Of Scope

- full source tree enumeration;
- downloading binaries and image bytes for storage;
- training/inference semantics from repository code.
- unbounded crawl growth beyond configured queue and page budgets.
