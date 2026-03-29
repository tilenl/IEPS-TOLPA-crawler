1. Dopiši še ogromno več besed za preference scoring, da bo Boljše scoral linke! V crawler/src/resources/keywords.json se to vpiše!
2. Spremeni dodajanje linkov v frontier tako, da če je že max število linkov notri, vržeš vn frontier link z najmanjšim preferential scorom, če ima novi link višji score kot 0. Tako skrbiš, da se ne dodajo popolnoma vsi linki v database in poskrbiš, da se novi link z višjim sporom vseeno doda notri v frontier.
3. For keywords.json is it better if they are singleton based? And that it gets scored it the word is found even as a subset of another word. For example, this would enable us to use "semantic" keyword, and it would match it if the word was in a "semantic segmentation", "semantic-segmentation", "semanticsegmentation"... i think that this solution would need us to lower the scoring, as many more matches would be found, and the scores would get to 1.0 really fast.
4. The crawler didn't stop after fetching all 100 pages. Some pages were marked as ERROR, and other were marked as HTML. Was this the cause that the crawler stopped?
5. Fix why the [https://github.com/onnx/models/blob/main/validated/vision/classification/mobilenet](https://github.com/onnx/models/blob/main/validated/vision/classification/mobilenet), [https://github.com/onnx/models/blob/main/validated/vision/classification/resnet](https://github.com/onnx/models/blob/main/validated/vision/classification/resnet), [https://github.com/onnx/models/blob/main/validated/vision/classification/squeezenet](https://github.com/onnx/models/blob/main/validated/vision/classification/squeezenet) are fetched as ERROR -> ROBOTS_DISALLOWED. Why were they even added to the frontier if the robot disallows them? Any URLs that are not permited by the robot should not be added to the frontier. But Firstly reason why they were disallowed, as they should be allowed to be fetched.

KO BO PREFERENTIAL SCORING DELOVAL:

### E. Optional “notebook level 3” (heavier)

- Add a second scoring mode: **cosine similarity** between a configurable **target description** string (notebook cell 20) and link neighborhood (requires a small vectorization dependency or a minimal hand-rolled tokenizer + dot product). Use only if the course allows extra dependencies/CPU.

### F. Frontier strategy (notebook N-best)

- Notebook cell 13–14: **N-best first** reduces “burying” good links. That is largely **frontier policy** ([TS-07](ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/technical-specifications/TS-07-frontier-and-priority-dequeue.md) area), not scoring alone—treat as a follow-up if the queue always expands one hyper-relevant branch first.

