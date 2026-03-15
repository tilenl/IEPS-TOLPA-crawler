# Domain And Scope Definition

## Primary Crawl Domain

- primary domain: `github.com`
- target content: repositories related to image segmentation tasks

## Seeds

- topic pages:
  - `/topics/image-segmentation`
  - `/topics/semantic-segmentation`
  - `/topics/medical-image-segmentation`
- canonical repositories (SAM, MMSeg, U-Net, DeepLab, etc.)
- optional low-priority generic model hubs for cross-link expansion

## Scope Rules

- crawl repository landing pages and topic pages;
- follow extracted links if relevant;
- do not rely on disallowed paths from robots;
- do not clone repos or traverse full repo trees.

## Relevance Domain

- primary segmentation keywords (high priority);
- secondary adjacent CV terms (lower priority fallback);
- score computed at ingestion, before frontier insert.

## Out Of Scope

- full source tree enumeration;
- downloading binaries and image bytes for storage;
- training/inference semantics from repository code.
