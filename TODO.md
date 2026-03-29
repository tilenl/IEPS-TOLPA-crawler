1. Ker smo omejeni SAMO na domeno github.com, ali se bo zgodilo, da bo prvi vzel URL in ga dobil, ostali crawlerji pa bodo vzeli vse naslednje URLje in ker jim bucket sporoči da morajo 5s počakati, ga nazaj enquijajo. Ampak v 5 sekundah bodo burnali skozi vse URL-je, ker so vsi limitirano na 5 sekund.

Se pravi rabimo tako popraviti, da vzame naslednji URL in čaka dokler mu bucket ne dovoli fetchati. Tako se izognemo temu problemu, ker smo samo na eni domeni. 

Se pravi ko kliče fetchNextFrontier, interno ta funkcija pokliče za domeno github.com ali je že na voljo. Če še ni, potem sleep thread dokler ga ne pokliče bucket. Bucket na vsake 5s zbudi nek random thread, ki dobi potem next frontier. Ni najboljša ideja iskreno, je pa ideja. Boš z čatkotom prediskutiral.

Če bi to prej vedel bi bilo dost bolj simpl implementirati!

1. Kok anchor texta upošteva crawler? Kje se to nastavi?
2. Dopiši še ogromno več besed za preference scoring, da bo Boljše scoral linke! V crawler/src/resources/keywords.json se to vpiše!
3. Spremeni dodajanje linkov v frontier tako, da če je že max število linkov notri, vržeš vn frontier link z najmanjšim preferential scorom, če ima novi link višji score kot 0. Tako skrbiš, da se ne dodajo popolnoma vsi linki v database in poskrbiš, da se novi link z višjim sporom vseeno doda notri v frontier.





KO BO PREFERENTIAL SCORING DELOVAL:

### E. Optional “notebook level 3” (heavier)

- Add a second scoring mode: **cosine similarity** between a configurable **target description** string (notebook cell 20) and link neighborhood (requires a small vectorization dependency or a minimal hand-rolled tokenizer + dot product). Use only if the course allows extra dependencies/CPU.

### F. Frontier strategy (notebook N-best)

- Notebook cell 13–14: **N-best first** reduces “burying” good links. That is largely **frontier policy** ([TS-07](ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/technical-specifications/TS-07-frontier-and-priority-dequeue.md) area), not scoring alone—treat as a follow-up if the queue always expands one hyper-relevant branch first.

