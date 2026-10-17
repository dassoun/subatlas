package com.sensitivemobility.subatlas

/** Pays (code ISO 3166-1, nom français, nom anglais) : sert à retrouver le drapeau d'un pays saisi à la main. */
private const val COUNTRY_TABLE = """
AF|Afghanistan|Afghanistan
ZA|Afrique du Sud|South Africa
AL|Albanie|Albania
DZ|Algérie|Algeria
DE|Allemagne|Germany
AD|Andorre|Andorra
AO|Angola|Angola
AI|Anguilla|Anguilla
AG|Antigua-et-Barbuda|Antigua and Barbuda
SA|Arabie saoudite|Saudi Arabia
AR|Argentine|Argentina
AM|Arménie|Armenia
AW|Aruba|Aruba
AU|Australie|Australia
AT|Autriche|Austria
AZ|Azerbaïdjan|Azerbaijan
BS|Bahamas|Bahamas
BH|Bahreïn|Bahrain
BD|Bangladesh|Bangladesh
BB|Barbade|Barbados
BE|Belgique|Belgium
BZ|Belize|Belize
BJ|Bénin|Benin
BM|Bermudes|Bermuda
BT|Bhoutan|Bhutan
BY|Biélorussie|Belarus
MM|Birmanie|Myanmar
BO|Bolivie|Bolivia
BA|Bosnie-Herzégovine|Bosnia and Herzegovina
BW|Botswana|Botswana
BR|Brésil|Brazil
BN|Brunéi|Brunei
BG|Bulgarie|Bulgaria
BF|Burkina Faso|Burkina Faso
BI|Burundi|Burundi
KH|Cambodge|Cambodia
CM|Cameroun|Cameroon
CA|Canada|Canada
CV|Cap-Vert|Cape Verde
CL|Chili|Chile
CN|Chine|China
CY|Chypre|Cyprus
CO|Colombie|Colombia
KM|Comores|Comoros
CG|Congo|Congo
CD|République démocratique du Congo|DR Congo
KR|Corée du Sud|South Korea
KP|Corée du Nord|North Korea
CR|Costa Rica|Costa Rica
CI|Côte d'Ivoire|Ivory Coast
HR|Croatie|Croatia
CU|Cuba|Cuba
CW|Curaçao|Curaçao
DK|Danemark|Denmark
DJ|Djibouti|Djibouti
DM|Dominique|Dominica
EG|Égypte|Egypt
SV|Salvador|El Salvador
AE|Émirats arabes unis|United Arab Emirates
EC|Équateur|Ecuador
ER|Érythrée|Eritrea
ES|Espagne|Spain
EE|Estonie|Estonia
US|États-Unis|United States
ET|Éthiopie|Ethiopia
FJ|Fidji|Fiji
FI|Finlande|Finland
FR|France|France
GA|Gabon|Gabon
GM|Gambie|Gambia
GE|Géorgie|Georgia
GI|Gibraltar|Gibraltar
GH|Ghana|Ghana
GR|Grèce|Greece
GD|Grenade|Grenada
GL|Groenland|Greenland
GP|Guadeloupe|Guadeloupe
GU|Guam|Guam
GT|Guatemala|Guatemala
GF|Guyane|French Guiana
GN|Guinée|Guinea
GQ|Guinée équatoriale|Equatorial Guinea
GY|Guyana|Guyana
HT|Haïti|Haiti
HN|Honduras|Honduras
HK|Hong Kong|Hong Kong
HU|Hongrie|Hungary
KY|Îles Caïmans|Cayman Islands
CK|Îles Cook|Cook Islands
FO|Îles Féroé|Faroe Islands
MV|Maldives|Maldives
MH|Îles Marshall|Marshall Islands
SB|Îles Salomon|Solomon Islands
TC|Îles Turques-et-Caïques|Turks and Caicos Islands
VG|Îles Vierges britanniques|British Virgin Islands
VI|Îles Vierges américaines|US Virgin Islands
IN|Inde|India
ID|Indonésie|Indonesia
IQ|Irak|Iraq
IR|Iran|Iran
IE|Irlande|Ireland
IS|Islande|Iceland
IL|Israël|Israel
IT|Italie|Italy
JM|Jamaïque|Jamaica
JP|Japon|Japan
JO|Jordanie|Jordan
KZ|Kazakhstan|Kazakhstan
KE|Kenya|Kenya
KG|Kirghizistan|Kyrgyzstan
KI|Kiribati|Kiribati
KW|Koweït|Kuwait
LA|Laos|Laos
LS|Lesotho|Lesotho
LV|Lettonie|Latvia
LB|Liban|Lebanon
LR|Libéria|Liberia
LY|Libye|Libya
LI|Liechtenstein|Liechtenstein
LT|Lituanie|Lithuania
LU|Luxembourg|Luxembourg
MO|Macao|Macau
MK|Macédoine du Nord|North Macedonia
MG|Madagascar|Madagascar
MY|Malaisie|Malaysia
MW|Malawi|Malawi
ML|Mali|Mali
MT|Malte|Malta
MA|Maroc|Morocco
MQ|Martinique|Martinique
MU|Maurice|Mauritius
MR|Mauritanie|Mauritania
YT|Mayotte|Mayotte
MX|Mexique|Mexico
FM|Micronésie|Micronesia
MD|Moldavie|Moldova
MC|Monaco|Monaco
MN|Mongolie|Mongolia
ME|Monténégro|Montenegro
MS|Montserrat|Montserrat
MZ|Mozambique|Mozambique
NA|Namibie|Namibia
NR|Nauru|Nauru
NP|Népal|Nepal
NI|Nicaragua|Nicaragua
NE|Niger|Niger
NG|Nigéria|Nigeria
NU|Niue|Niue
NO|Norvège|Norway
NC|Nouvelle-Calédonie|New Caledonia
NZ|Nouvelle-Zélande|New Zealand
OM|Oman|Oman
UG|Ouganda|Uganda
UZ|Ouzbékistan|Uzbekistan
PK|Pakistan|Pakistan
PW|Palaos|Palau
PS|Palestine|Palestine
PA|Panama|Panama
PG|Papouasie-Nouvelle-Guinée|Papua New Guinea
PY|Paraguay|Paraguay
NL|Pays-Bas|Netherlands
PE|Pérou|Peru
PH|Philippines|Philippines
PL|Pologne|Poland
PF|Polynésie française|French Polynesia
PR|Porto Rico|Puerto Rico
PT|Portugal|Portugal
QA|Qatar|Qatar
CF|République centrafricaine|Central African Republic
DO|République dominicaine|Dominican Republic
CZ|Tchéquie|Czechia
RE|La Réunion|Réunion
RO|Roumanie|Romania
GB|Royaume-Uni|United Kingdom
RU|Russie|Russia
RW|Rwanda|Rwanda
BL|Saint-Barthélemy|Saint Barthélemy
KN|Saint-Christophe-et-Niévès|Saint Kitts and Nevis
SM|Saint-Marin|San Marino
MF|Saint-Martin|Saint Martin
SX|Sint Maarten|Sint Maarten
VC|Saint-Vincent-et-les-Grenadines|Saint Vincent and the Grenadines
LC|Sainte-Lucie|Saint Lucia
WS|Samoa|Samoa
AS|Samoa américaines|American Samoa
ST|Sao Tomé-et-Principe|São Tomé and Príncipe
SN|Sénégal|Senegal
RS|Serbie|Serbia
SC|Seychelles|Seychelles
SL|Sierra Leone|Sierra Leone
SG|Singapour|Singapore
SK|Slovaquie|Slovakia
SI|Slovénie|Slovenia
SO|Somalie|Somalia
SD|Soudan|Sudan
SS|Soudan du Sud|South Sudan
LK|Sri Lanka|Sri Lanka
SE|Suède|Sweden
CH|Suisse|Switzerland
SR|Suriname|Suriname
SZ|Eswatini|Eswatini
SY|Syrie|Syria
TJ|Tadjikistan|Tajikistan
TW|Taïwan|Taiwan
TZ|Tanzanie|Tanzania
TD|Tchad|Chad
TH|Thaïlande|Thailand
TL|Timor oriental|Timor-Leste
TG|Togo|Togo
TO|Tonga|Tonga
TT|Trinité-et-Tobago|Trinidad and Tobago
TN|Tunisie|Tunisia
TM|Turkménistan|Turkmenistan
TR|Turquie|Turkey
TV|Tuvalu|Tuvalu
UA|Ukraine|Ukraine
UY|Uruguay|Uruguay
VU|Vanuatu|Vanuatu
VA|Vatican|Vatican City
VE|Venezuela|Venezuela
VN|Viêt Nam|Vietnam
WF|Wallis-et-Futuna|Wallis and Futuna
YE|Yémen|Yemen
ZM|Zambie|Zambia
ZW|Zimbabwe|Zimbabwe
"""

/** Autres écritures courantes d'un même pays. */
private val COUNTRY_ALIASES = mapOf(
    "USA" to "US", "ÉTATS UNIS" to "US", "ETATS UNIS" to "US", "UNITED STATES OF AMERICA" to "US",
    "UK" to "GB", "GRANDE-BRETAGNE" to "GB", "GREAT BRITAIN" to "GB", "ANGLETERRE" to "GB", "ENGLAND" to "GB",
    "ÉCOSSE" to "GB", "SCOTLAND" to "GB", "PAYS DE GALLES" to "GB", "WALES" to "GB",
    "BIRMANIE (MYANMAR)" to "MM", "REPUBLIQUE TCHEQUE" to "CZ", "CZECH REPUBLIC" to "CZ", "HOLLANDE" to "NL",
    "COTE D'IVOIRE" to "CI", "CÔTE D’IVOIRE" to "CI", "TURQUIE" to "TR", "TÜRKIYE" to "TR",
    "COREE" to "KR", "KOREA" to "KR", "EMIRATS ARABES UNIS" to "AE", "UAE" to "AE", "DUBAI" to "AE",
    "TAHITI" to "PF", "BORA BORA" to "PF", "REUNION" to "RE", "ILE MAURICE" to "MU", "ÎLE MAURICE" to "MU",
    "CAP VERT" to "CV", "SAINT MARTIN" to "MF", "TIMOR-LESTE" to "TL", "SWAZILAND" to "SZ",
)

private class CountryEntry(val code: String, val fr: String, val en: String)

private val countries: List<CountryEntry> by lazy {
    COUNTRY_TABLE.lines().mapNotNull { line ->
        val p = line.trim().split("|")
        if (p.size == 3) CountryEntry(p[0], p[1], p[2]) else null
    }
}

private fun normalizeCountry(text: String): String {
    val accents = "àâäáãåçéèêëíìîïñóòôöõúùûüýÿ"
    val plain = "aaaaaaceeeeiiiinooooouuuuyy"
    return text.trim().lowercase()
        .map { c -> accents.indexOf(c).let { if (it >= 0) plain[it] else c } }
        .joinToString("")
        .replace('’', '\'')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")
}

private val byName: Map<String, String> by lazy {
    buildMap {
        countries.forEach {
            put(normalizeCountry(it.fr), it.code)
            put(normalizeCountry(it.en), it.code)
            put(it.code.lowercase(), it.code)
        }
        COUNTRY_ALIASES.forEach { (alias, code) -> put(normalizeCountry(alias), code) }
    }
}

/** Émoji drapeau d'un code pays à deux lettres (indicateurs régionaux Unicode). */
private fun flagOf(code: String): String =
    code.uppercase().map { c -> "\uD83C" + (0xDDE6 + (c - 'A')).toChar() }.joinToString("")

/** Drapeau du pays saisi (nom français ou anglais, ou code ISO) ; `null` s'il n'est pas reconnu. */
internal fun countryFlag(country: String): String? = byName[normalizeCountry(country)]?.let(::flagOf)

/** Pays de la table dont le nom contient la saisie : (nom dans la langue demandée, drapeau). */
internal fun countrySuggestions(query: String, french: Boolean, limit: Int): List<Pair<String, String>> {
    val q = normalizeCountry(query)
    if (q.isEmpty()) return emptyList()
    return countries
        .map { (if (french) it.fr else it.en) to it.code }
        .filter { normalizeCountry(it.first).let { n -> n.startsWith(q) || n.contains(" $q") } }
        .take(limit)
        .map { it.first to flagOf(it.second) }
}
