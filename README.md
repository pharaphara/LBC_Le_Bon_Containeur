<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/brand/banner-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="assets/brand/banner-light.png">
  <img alt="le bon container. Il a la tête de l'emploi. MCP server, headed Chromium, Docker." src="assets/brand/banner-light.png" width="100%">
</picture>

**Français** · [English](README.en.md)

# le bon container

**Il a la tête de l'emploi.**

Explorer les annonces [Leboncoin](https://www.leboncoin.fr) avec une IA, dans un
vrai navigateur, à travers [MCP](https://modelcontextprotocol.io).

![build](https://github.com/pharaphara/le-bon-container/actions/workflows/build.yml/badge.svg)

Un conteneur, neuf outils, et des résultats exploitables par un assistant : les
repères de marché d'abord, un tableau compact ensuite, le détail complet sur le
disque, et des numéros d'annonces qui ne bougent jamais.

```
search "vtt electrique", place 12, price max 1500

search "vtt-electrique-8778" | 47 announced | 46 collected | 1 calls
prices: median 915 EUR, middle half 500 EUR to 1 250 EUR, range 30 EUR to 1 500 EUR
market: 45 private, 1 pro, median age 118 d, 6 bumped since first posted
---
  n      price  place              age   by   title
  1    1 050 €  Auriac-Lagast (1…  14 j  P    VTT electrique
  2      800 €  Rodez (12)         50 j  P    Vtt electrique giant
  3    1 400 €  Nauviale (12)      30 j  P    Vtt electrique
  …
!! condition the site applied: global_BDC
- more: results(offset=25) for the remaining 21
```

Les catégories et les valeurs de filtres se disent avec les mots du site
(« Voitures », « électrique », « particulier »). Les outils répondent en anglais,
comme le code. Seule la documentation est
bilingue.

## Démarrage rapide

```bash
docker compose up -d
```

Puis on branche son client dessus : voir [brancher un
client](#brancher-un-client) juste en dessous, pour Claude Code, Claude, ChatGPT
et Codex.

Rien à compiler et aucun JDK à installer : l'image est construite et publiée par
l'intégration continue.

La première fois, lancez plutôt `docker compose up` sans `-d` : le conteneur
affiche un message d'accueil avec les deux adresses et l'état du profil. Ensuite,
`docker compose logs lbc` le redonne à tout moment. Docker n'affiche rien de
l'intérieur d'un conteneur démarré en arrière-plan, c'est une limite de Docker et
non un oubli.

**Une fois, au début**, ouvrir <http://localhost:7900>. Vous regardez le navigateur
du conteneur, et c'est là qu'un humain fait la seule chose qu'aucun outil ne
devrait faire : passer le contrôle anti-robot, et se connecter au site si vous le
souhaitez.

Ce n'est pas facultatif, et il vaut la peine de savoir pourquoi. Mesuré sur un
conteneur neuf : le site rend ses pages sans broncher, mais refuse les appels de
données par un 403 portant un marqueur anti-robot, et au bout de quelques requêtes
il finit par contrôler la page elle-même. Un profil tout neuf n'a simplement aucune
validation. Passez le contrôle une fois à la main, et le profil s'en souvient.
Savoir si un compte apporte quelque chose en plus n'a pas été mesuré, donc ce
document ne l'affirme pas.

## Brancher un client

Le serveur parle MCP en HTTP sur `http://localhost:8788/mcp`. Voici les quatre
cas les plus courants.

**Claude Code**

```bash
claude mcp add --transport http lbc http://localhost:8788/mcp
```

**Claude, application de bureau**

Dans le fichier de configuration des serveurs MCP :

```json
{ "mcpServers": { "lbc": { "type": "http", "url": "http://localhost:8788/mcp" } } }
```

Les versions récentes permettent aussi d'ajouter un connecteur personnalisé
depuis les réglages, en collant la même adresse.

**ChatGPT**

Il faut une adresse **publique en HTTPS** : ChatGPT n'atteindra jamais votre
`localhost`. Mettez donc un reverse proxy avec TLS devant le port 8788, par
exemple `lbc.exemple.org`, puis ajoutez-le comme connecteur personnalisé dans les
réglages de votre compte. La disponibilité de cette fonction dépend de votre offre
et de vos réglages, ce document ne peut pas le savoir à votre place. Et n'exposez
rien sans authentification devant : ces outils pilotent un navigateur qui porte
votre session.

**Codex**

Dans `~/.codex/config.toml` :

```toml
[mcp_servers.lbc]
command = "npx"
args = ["-y", "mcp-remote", "http://localhost:8788/mcp"]
```

Cette forme passe par une passerelle, donc elle marche quelle que soit la version.
Si la vôtre accepte directement une adresse HTTP, la déclarer ainsi évite la
passerelle.

**N'importe quel autre client qui ne parle que stdio** se branche de la même
façon, par une passerelle du type `mcp-remote` pointée sur la même adresse.

## Les outils

| Outil | Ce qu'il fait |
|---|---|
| `search` | Collecte une recherche. Un rendu de page, puis la pagination par appels de données. Rend les repères, puis un tableau. |
| `results` | Relit ce qui a été collecté : filtrer et trier, hors ligne, autant de fois qu'on veut. |
| `ad` | Tout sur une annonce, sans réseau. `refresh=true` va revérifier sur le site. |
| `stats` | Le marché en chiffres, éventuellement groupé par marque, année, ville ou vendeur. |
| `filters` | Ce que le site a retenu d'une URL de recherche, et ce qu'il a jeté en silence. |
| `categories` | Les 45 catégories mesurées, ou le sondage d'un identifiant à la demande. |
| `searches` | Ce qui est sur le disque, et la recherche que les autres outils visent par défaut. |
| `forget` | Supprime une recherche et ses annonces. |
| `status` | Comment le conteneur est câblé, et où cliquer si un mur apparaît. |

## Comment ça marche

Quatre choses ont été mesurées plutôt que supposées, et elles font toute la
conception.

**La page livre sa propre charge de recherche.** Une page de résultats embarque la
requête exacte que le site a dérivée de l'URL. Donc rien n'est inventé : on
construit une URL avec des paramètres mesurés un par un contre le total que le site
annonce, on la rend une fois, puis on rejoue sa propre charge pour parcourir le
reste. Le rendu est le poste coûteux, et il n'a lieu qu'une fois.

**La pagination se fait sur `offset` seul.** Le `pivot` que le site renvoie
ressemble à un curseur mais n'est qu'une liste d'identifiants déjà montrés : le
rejouer depuis l'offset zéro rend les mêmes annonces. `max_pages` vaut 2 pour 122
annonces, donc il est affiché et jamais utilisé comme borne.

**La liste porte déjà tout.** Mesuré sur une annonce : 6588 caractères de
description dans les résultats de recherche, exactement autant que sur sa propre
page, avec tous les attributs et toutes les photos. Donc `ad` ne coûte rien et ne
touche pas au réseau. Visiter une annonce n'apprend qu'une seule chose que la liste
ignore : si le prix a bougé ou si elle est vendue depuis.

**L'API se répète.** Trois réponses totalisant 224 lignes ne portaient que 120
identifiants distincts. Le dédoublonnage n'est pas une précaution ici, c'est une
obligation, et le nombre affiché est toujours celui des annonces uniques.

**Et quand les appels de données sont refusés, les pages rendues marchent
encore.** Tant qu'un profil n'a pas de validation, on lit ce que la page rend
elle-même, une trentaine d'annonces à la fois, et on pagine par l'URL. C'est plus
lent, un rendu par page, et la réponse le dit en toutes lettres au lieu de faire
semblant. Passez le contrôle une fois et la voie rapide s'ouvre d'elle-même.

Et une dernière chose, qui ne tient pas au site : **les numéros ne bougent
jamais.** Une annonce garde le numéro reçu la première fois qu'on l'a vue.
`ad(number=7)` désigne la même annonce dix messages plus tard, après deux pages de
plus et un tri par prix. Cette seule propriété rend supportable une longue
conversation avec une place de marché.

## Les catégories sont mesurées, pas recopiées

Quarante-cinq, et chaque identifiant vient des données du site plutôt que des notes
de quelqu'un. Chaque annonce porte à la fois son `category_id` et son
`category_name`, donc parcourir le site trié par date rend des paires canoniques à
la douzaine : 210 annonces sur six pages en ont donné 44 d'un coup.

Cette méthode a immédiatement corrigé deux identifiants qui avaient l'air justes et
ne l'étaient pas. Les jouets, c'est 41, alors que 40 est Collection. Le jardin,
c'est 52, alors que 32 est l'équipement industriel. L'un comme l'autre auraient
rendu un marché vide sans la moindre erreur, et c'est bien le défaut qui compte
ici : un mauvais identifiant n'échoue pas, il ne trouve simplement rien.

Un nom jamais mesuré est donc refusé plutôt qu'utilisé. N'importe quel identifiant
numérique fonctionne directement, et `categories(verify=["70"])` en sonde un sur le
champ et vous dit comment le site l'appelle.

## Aucun déguisement, jamais

Le mur sur ce genre de site est un défi JavaScript, pas une empreinte. Six sessions
sur quatre familles d'empreintes maquillées ont toutes été refusées, là où un vrai
navigateur avec une vraie session est passé sans encombre. Mieux se déguiser est
donc la mauvaise dimension.

Alors ce conteneur pilote un vrai navigateur avec votre propre session. Il ne
bricole pas `navigator`, il ne falsifie pas d'agent utilisateur, il ne passe même
pas le drapeau qui masque l'automatisation, et il n'appelle jamais de service de
résolution de captcha. Devant un contrôle il attend, parce qu'un vrai navigateur en
vient souvent à bout tout seul, puis il coche au plus une case. Un puzzle est
l'endroit où il s'arrête et vous renvoie vers <http://localhost:7900>. Un test
impose tout cela : quinze motifs sont bannis du dépôt, et le détecteur est
lui-même éprouvé contre un déguisement connu.

## Comment le navigateur est démarré, et pourquoi ça compte plus que tout

C'est la découverte la plus utile du projet, et elle a été mesurée en comparant ce
conteneur à un navigateur qui lit ce genre de site tous les jours depuis des mois
sans jamais se faire restreindre.

| Vu depuis une page | Navigateur démarré par la bibliothèque | Navigateur démarré normalement |
|---|---|---|
| `navigator.webdriver` | `true` | `false` |
| `navigator.plugins` | 0 | 5 |

Laisser Playwright lancer le navigateur ajoute le drapeau d'automatisation, et la
page le voit. Démarrer un navigateur de bureau ordinaire avec un port de débogage
ouvert, puis simplement s'y rattacher, ne l'ajoute pas. C'était la seule différence
structurelle entre les deux installations.

C'est pourquoi l'image démarre Chromium elle-même et s'y rattache, et c'est ce que
fait `LBC_BROWSER: attach`. Rien n'est falsifié et rien n'est masqué : un drapeau
n'est simplement pas ajouté. On ne bricole pas `navigator`, on ne passe pas le
fameux drapeau qui masque l'automatisation, et la différence tient uniquement à la
façon de lancer le programme.

`LBC_BROWSER: launch` rend l'ancien comportement, plus simple et plus bruyant.

## Rien en silence

Chaque réduction est comptée et montrée, sur une ligne commençant par `!!`. Un
critère que le site a ignoré, une annonce écartée parce qu'elle venait d'une autre
rubrique, un doublon, une collecte arrêtée avant la fin, une recherche servie
depuis le disque : chacune produit un chiffre dans la réponse.

C'est le défaut contre lequel ce projet est bâti. Un tableau qui a perdu un tiers de
ses lignes sans le dire se lit exactement comme un marché étroit, et un assistant
n'a aucun moyen de faire la différence.

## Configuration

Tout a une valeur par défaut qui fonctionne. On ne règle que ce dont on a besoin,
dans `compose.yaml`.

| Variable | Défaut | Ce que c'est |
|---|---|---|
| `LBC_DATA` | `/data` | Un dossier par recherche. À monter pour conserver ses recherches. |
| `LBC_PROFILE` | `/profile` | Le profil du navigateur, pour qu'une connexion survive à un redémarrage. |
| `LBC_HEADLESS` | `false` | Un humain doit pouvoir voir la page pour passer un contrôle. |
| `LBC_CDP_URL` | vide | Se rattacher à un navigateur déjà lancé au lieu d'en démarrer un. |
| `LBC_VNC_PASSWORD` | vide | À renseigner avant d'exposer le port 7900 ailleurs que sur la machine locale. |
| `LBC_VIEWER` | `on` | `off` supprime l'affichage et le visualiseur. |
| `LBC_PACE_CALL_MIN_MS` | `1500` | Pause la plus courte entre deux appels de données. |
| `LBC_PACE_CALL_MAX_MS` | `3500` | La plus longue, tirée au hasard entre les deux. |
| `LBC_PACE_RENDER_MIN_MS` | `6000` | Entre deux rendus de page, qui coûtent bien plus cher au site. |
| `LBC_PACE_RENDER_MAX_MS` | `10000` | Idem, borne haute. |
| `LBC_PACE_CALLS_PER_MINUTE` | `20` | Plafond, quoi que disent les pauses. |
| `LBC_PACE_PAGES_MAX` | `20` | Butée dure sur le nombre de pages par collecte. |
| `LBC_PACE_RENDERED_PAGES_MAX` | `3` | Pages par appel sur la voie lente. |
| `LBC_MAX_ADS` | `2000` | Butée dure sur le nombre d'annonces par recherche. |
| `SERVER_PORT` | `8788` | Le port d'écoute de MCP. |

**Apportez votre navigateur.** Avec `LBC_CDP_URL` pointant sur un Chromium déjà
lancé avec le pilotage à distance activé, le conteneur s'y rattache et utilise son
premier contexte, c'est-à-dire le vrai profil avec la vraie session. Rien n'est
lancé et rien n'est fermé : le navigateur est le vôtre.

**Derrière un reverse proxy.** Le port 7900 est une page web, donc n'importe quel
reverse proxy peut la mettre derrière un nom à vous, par exemple
`lbc.exemple.org`. Renseignez `LBC_VNC_PASSWORD` d'abord : qui ouvre cette page
pilote un navigateur qui porte votre session, et c'est exactement pour cela que le
fichier compose lie les deux ports à la machine locale tant que vous n'en décidez
pas autrement.

**Derrière un VPN.** Rien à coder : Docker sait déjà le faire avec
`network_mode: "service:<vpn>"`, en déplaçant la publication des ports sur le
conteneur VPN. Mais réfléchissez avant, parce que c'est souvent contre-productif
ici. Une sortie de VPN commercial est une adresse de centre de données, et la
réputation de l'adresse est précisément ce que les contrôles anti-robot notent :
le mur apparaîtra plus souvent, pas moins. Le cas qui a du sens est l'inverse, un
VPN vers votre propre réseau, qui garde une adresse résidentielle et permet de
faire tourner le conteneur ailleurs tout en sortant de chez vous.

**Où vivent vos données.** Un dossier par recherche sous `/data` :

```
/data/<recherche>/
  search.json    l'url, la charge que le site en a dérivée, les totaux, où la pagination s'est arrêtée
  ads.jsonl      une ligne par annonce, l'objet du site lui-même, numéroté
  skipped.jsonl  les annonces écartées, avec le motif, pour vérifier que le filet était juste
  seen.tsv       le registre identifiant vers numéro, et c'est pourquoi les numéros ne bougent pas
  ads/<id>.json  ce qu'une revérification a ramené
```

C'est l'objet brut qui est stocké, pas une vue compacte. La description complète y
est de toute façon, et recalculer la vue à la lecture évite des colonnes périmées le
jour où la formule change.

**La cadence se règle dans le compose.** Ce sont les nombres qui décident si le
site vous voit comme un visiteur ou comme une nuisance. Les valeurs par défaut
viennent d'un navigateur qui lit ce genre de site tous les jours depuis des mois :
une à trois secondes entre deux appels, six à dix entre deux rendus de page. Aller
plus vite ne collecte pas plus, ça collecte moins longtemps.

## Si l'accès est temporairement restreint

Ça arrive, et c'est mesuré : un profil sans validation qui insiste finit par voir
« accès temporairement restreint ». Ce n'est pas un contrôle à passer, c'est une
pause à prendre. Cliquer n'y changera rien, et réessayer aggrave les choses.

L'outil le reconnaît et le dit autrement qu'un simple mur : il vous demande de vous
arrêter, pas d'aller cliquer. Deux choses à garder en tête. Votre adresse est
probablement partagée avec vos autres outils, donc insister depuis ce conteneur
dégrade ce qui marche ailleurs. Et la sortie, ce n'est pas un contournement : c'est
un profil avec une vraie session et une cadence plus lente.

## Développement

```bash
mvn verify              # la logique métier : sans navigateur, sans réseau, sur échantillons figés
mvn spring-boot:run     # le lancer sur sa machine
docker build -t lbc .   # l'image
```

Playwright embarque son propre Node pour cinq plateformes, et c'est ainsi qu'il
garantit la version dont il a besoin. L'image garde celle qu'elle exécute et jette
les quatre autres, soit 155 Mo.

## Feuille de route

**La messagerie par MCP**, en lecture et en réponse, pour qu'un assistant puisse
mener seul une négociation sur un objet. La ligne à tenir est la profondeur contre
la largeur : beaucoup d'échanges dans un même fil, c'est une négociation ; beaucoup
de fils ouverts avec des inconnus, c'est du spam. Les plafonds porteront donc sur
les fils ouverts par jour et non sur les réponses, l'autonomie sera un niveau
explicite et non un défaut, tout ce qui part sera journalisé, et la règle selon
laquelle un assistant n'énonce jamais un fait faux a sa place dans la description
de l'outil, là où elle sera vraiment lue.

**La connexion au site** sans passer par le visualiseur, pour qui préfère mettre
des identifiants dans le fichier compose. Ce restera une option et non le défaut :
un mot de passe de place de marché en clair est un mauvais échange contre un geste
qu'on ne fait qu'une fois.

## Usage responsable

C'est un outil d'automatisation personnelle. Il pilote votre navigateur et votre
session, à un rythme humain, et lit des pages que vous pourriez ouvrir vous-même.
L'accès automatisé est contraire aux conditions d'utilisation du site, donc ce que
vous en faites vous regarde. Gardez-le pour vos propres recherches et vos propres
conversations : il n'existe délibérément aucun moyen d'écrire à plusieurs personnes
à la fois, et il n'y en aura jamais.

## Licence

MIT. Voir [LICENSE](LICENSE).

Projet indépendant, non affilié à Leboncoin.
