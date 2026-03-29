1. Ker smo omejeni SAMO na domeno github.com, ali se bo zgodilo, da bo prvi vzel URL in ga dobil, ostali crawlerji pa bodo vzeli vse naslednje URLje in ker jim bucket sporoči da morajo 5s počakati, ga nazaj enquijajo. Ampak v 5 sekundah bodo burnali skozi vse URL-je, ker so vsi limitirano na 5 sekund.

Se pravi rabimo tako popraviti, da vzame naslednji URL in čaka dokler mu bucket ne dovoli fetchati. Tako se izognemo temu problemu, ker smo samo na eni domeni. 

Se pravi ko kliče fetchNextFrontier, interno ta funkcija pokliče za domeno github.com ali je že na voljo. Če še ni, potem sleep thread dokler ga ne pokliče bucket. Bucket na vsake 5s zbudi nek random thread, ki dobi potem next frontier. Ni najboljša ideja iskreno, je pa ideja. Boš z čatkotom prediskutiral.

Če bi to prej vedel bi bilo dost bolj simpl implementirati!

1. Kok anchor texta upošteva crawler? Kje se to nastavi?
2. Dopiši še ogromno več besed za preference scoring, da bo Boljše scoral linke! V crawler/src/resources/keywords.json se to vpiše!
3. Dodej mu notebook, kjer smo še neke Naprednejše preference scoring tehnike pogledali in naj pogleda in evalvira, katero bi lahko implementirali, da bi Naš crawler boljše delal?
4. Dovoljena domena je SAMO github.com. Nobene njegove podbomene. Ali?? Nah, tudi njegove poddomene, ker so vseeno znotraj domene github.com
5. Spremeni dodajanje linkov v frontier tako, da če je že max število linkov notri, vržeš vn frontier link z najmanjšim preferential scorom, če ima novi link višji score kot 0. Tako skrbiš, da se ne dodajo popolnoma vsi linki v database in poskrbiš, da se novi link z višjim sporom vseeno doda notri v frontier.
6. Pod site tabelo so vsi siti, tudi tisti, ki niso znotraj naše domene. NE shraniti sitov, ki niso znotraj naše domene, ker do njih ne bomo nikoli dostopali ali uporabili.

