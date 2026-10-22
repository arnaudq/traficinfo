# TODOs

## create diff in ds function
so that we can anticipate bogus csv and abort further processing

## store and preserve historical data
some data gets changed

Anomalies probables (régressions dans le fichier récent)
Les vitesses et indicateurs horaires de 2022 ont disparu. v85ma_tcj, vtma_tcj, tir_tcj et hpm_* passent de 57 % de lignes renseignées à 0,15 %. Cela touche environ 790 lignes et 453 compteurs de tous types. Une partie des poids lourds 2021–2022 (mja_pl_*) est aussi devenue vide, environ 750 lignes.
Environ 300 lignes « sens cumulés » (canal Z) ont été supprimées sur les années 2016–2024, alors que les canaux 0 et 1 de ces mêmes compteurs sont toujours là. Exemple : RM612 Mireval (id 2) n’a plus de Z entre 2016 et 2024, mais en garde en 2013, 2014, 2025 et 2026. La baisse du nombre de lignes par an entre 2016 et 2023 vient de là.
Des sens 0 et 1 sont inversés sur des données historiques. Les valeurs sont permutées exactement : Bd Berthelot 2023 (108 :left_right_arrow: 7 090), Av. de Maurin 2023, Route de Lavérune 2023, Allée Henri II de Montmorency 2012 (1 :left_right_arrow: 1 169). C’est soit une correction, soit une erreur, et ça vaut le coup de demander à la Métropole.
La période de Rue du Faubourg du Courreau 2025 a changé (juin devient septembre) et les valeurs s’effondrent : de 866 à 16 véh/j pour le canal 1, de 1 774 à 226 pour le cumul.
Rue de la Thériaque : le libellé devient « RM986-E2 » et la commune passe de Lattes à Grabels. Les deux valeurs sont douteuses pour une rue de Montpellier.

## many street names are missing from csv
Most likely because they are also missing from OSM
Could use the libelle
Also missing from OSM are max speeds

## get speed limit from GPS position if missing
Could use the openstreetmap reverse REST API.
e.g. https://nominatim.openstreetmap.org/reverse?lat=43.26700822&lon=2.74608187&format=json
There are warnings that this API may not always return the right type of object (e.g. a building rather than a road)

##
OSMInfoFunction may update the cache at the same time as other functions.
Should we do all OSM processing only in that function ?

## Remove parameters that could be based on data source id

## MMM Dataset has 2 records with negative osmwayid

````
$ grep 35466 MMM_MMM_Comptage_20261012.csv
4010,962,35466,MONTPELLIER. Place du Marche aux Fleurs,2017,Sens cumulés,Sens cumulés,compteur ponctuel,comptage bidirectionnel,3,"{0,1,Z}",1,{2017},Z,24,24,17,0,,,,,,0,,,,,,,,,du 26/01/2017 au 03/02/2017,8,Place du Marché aux Fleurs,pedestrian,Montpellier,-6455414
4008,962,35466,MONTPELLIER. Place du Marche aux Fleurs,2017,Martyrs_Bonnier,Martyrs_Bonnier,compteur ponctuel,comptage bidirectionnel,3,"{0,1,Z}",1,{2017},0,18,18,13,0,2 (9h15 - 10h15),2 (17h45 - 18h45),,26,15,0,1,0,1,0,1,0,0,0,du 26/01/2017 au 03/02/2017,8,Place du Marché aux Fleurs,pedestrian,Montpellier,-6455414
4009,962,35466,MONTPELLIER. Place du Marche aux Fleurs,2017,Bonnier_Martyrs,Bonnier_Martyrs,compteur ponctuel,comptage bidirectionnel,3,"{0,1,Z}",1,{2017},1,6,6,4,0,1 (11h15 - 12h15),1 (17h15 - 18h15),,26,15,0,0,0,0,0,0,0,0,0,du 26/01/2017 au 03/02/2017,8,Place du Marché aux Fleurs,pedestrian,Montpellier,-6455414


$ grep 15598 MMM_MMM_Comptage_20261012.csv
5729,636,92372,MONTPELLIER. Rue du Professeur Blayac,2023,D_Neruda_Moulins,D_Neruda_Moulins,compteur permanent,comptage bidirectionnel,3,"{0,1,Z}",11,"{2016,2017,2018,2019,2020,2021,2022,2023,2024,2025",1,16541,16926,15598,597,1377 (7h00 - 8h00),1117 (13h00 - 14h00),99,70,58,760,1336,63,1051,56,955,29,336,13,du 01/01/2023 au 31/12/2023,364,Rue du Professeur Blayac,primary,Montpellier,133619331
7554,727,15598,MONTPELLIER. Tunnel de la Comedie,2015,Tunnel_Entrée Parking,Tunnel_Entrée Parking,compteur ponctuel,comptage unidirectionnel,1,{0},3,"{2012,2013,2015}",0,954,954,970,125,67 (11h30 - 12h30),89 (19h15 - 20h15),,26,15,124,25,4,60,8,51,8,24,3,du 17/05/2015 au 25/05/2015,8,,pedestrian,Montpellier,-5604751
7553,727,15598,MONTPELLIER. Tunnel de la Comedie,2013,Tunnel_Entrée Parking,Tunnel_Entrée Parking,compteur ponctuel,comptage unidirectionnel,1,{0},3,"{2012,2013,2015}",0,738,738,765,105,55 (10h00 - 11h00),90 (19h15 - 20h15),,29,17,105,18,2,50,7,39,6,19,2,du 12/11/2013 au 20/11/2013,8,,pedestrian,Montpellier,-5604751
7035,91,132505,MONTPELLIER. Rue Gabriel Luscan,2012,Baissette-Chekhal,Baissette-Chekhal,compteur ponctuel,comptage unidirectionnel,1,{0},1,{2012},0,384,384,373,6,38 (7h15 - 8h15),27 (18h00 - 19h00),10,28,17,6,34,1,17,0,25,0,8,0,du 19/03/2012 au 28/03/2012,9,Rue Gabriel Luscan,residential,Montpellier,230415598
7552,727,15598,MONTPELLIER. Tunnel de la Comedie,2012,Tunnel_Entrée Parking,Tunnel_Entrée Parking,compteur ponctuel,comptage unidirectionnel,1,{0},3,"{2012,2013,2015}",0,1010,1010,1093,128,81 (11h30 - 12h30),119 (19h45 - 20h45),,26,15,132,28,4,72,10,55,8,28,3,du 12/10/2012 au 22/10/2012,10,,pedestrian,Montpellier,-5604751
````

## Future datasources

Annuaire at https://trafic-routier.data.cerema.fr/acces-aux-donnees-de-trafic-routier-open-data-r25.html?lang=fr

Rouen: https://data.metropole-rouen-normandie.fr/explore/assets/comptages-et-enquetes-sur-le-reseau-routier/
Nimes: https://data.nimes-metropole.fr/explore/dataset/comptages-routiers-sur-la-voirie-de-lagglomeration/


DPT76: https://www.opendata76.fr/search?tags=comptage or https://www.data.gouv.fr/datasets/comptages-routiers-cd76-2022 (2022 only)

Données nationales: https://transport.data.gouv.fr/datasets/etat-de-circulation-en-temps-reel-sur-le-reseau-national-routier-non-concede

## list non compliant routes based on CEREMA criteria

https://www.cerema.fr/fr/actualites/velos-voitures-separation-ou-mixite-cles-choisir

## Other projects

https://avatar.cerema.fr/

https://cyclopolis.fr/carte-interactive?modal=filters (https://github.com/lavilleavelo/cyclopolis/tree/main)

https://github.com/TobiPeterG/OSMTraffic (dead but references the DATEX II standard)

## Standards and best practices

https://trafic-routier.data.cerema.fr/la-norme-europeenne-datex-ii-a58.html?lang=fr

https://datex2.eu/

https://trafic-routier.data.cerema.fr/comptages-a155.html?lang=fr



## Tools used

https://geojson.io/?map=12.24/43.62353/3.88035