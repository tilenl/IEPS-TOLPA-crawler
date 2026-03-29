1. The crawler didn't stop after fetching all 100 pages. Some pages were marked as ERROR, and other were marked as HTML. Was this the cause that the crawler stopped?
2. Fix why the [https://github.com/onnx/models/blob/main/validated/vision/classification/mobilenet](https://github.com/onnx/models/blob/main/validated/vision/classification/mobilenet), [https://github.com/onnx/models/blob/main/validated/vision/classification/resnet](https://github.com/onnx/models/blob/main/validated/vision/classification/resnet), [https://github.com/onnx/models/blob/main/validated/vision/classification/squeezenet](https://github.com/onnx/models/blob/main/validated/vision/classification/squeezenet) are fetched as ERROR -> ROBOTS_DISALLOWED. Why were they even added to the frontier if the robot disallows them? Any URLs that are not permited by the robot should not be added to the frontier. But Firstly reason why they were disallowed, as they should be allowed to be fetched.

KO BO PREFERENTIAL SCORING DELOVAL:

### E. Optional “notebook level 3” (heavier)

- Add a second scoring mode: **cosine similarity** between a configurable **target description** string (notebook cell 20) and link neighborhood (requires a small vectorization dependency or a minimal hand-rolled tokenizer + dot product). Use only if the course allows extra dependencies/CPU.

### F. Frontier strategy (notebook N-best)

- Notebook cell 13–14: **N-best first** reduces “burying” good links. That is largely **frontier policy** ([TS-07](ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/technical-specifications/TS-07-frontier-and-priority-dequeue.md) area), not scoring alone—treat as a follow-up if the queue always expands one hyper-relevant branch first.

