# Genera contrasenas fuertes en TU pantalla. No salen de esta maquina.
#
#   powershell -File scripts\generate-password.ps1
#
# Usa el generador criptografico del sistema (RandomNumberGenerator), no
# Get-Random, que es predecible y no sirve para secretos.

$ErrorActionPreference = "Stop"

# Sorteo uniforme sin sesgo: descarta los valores que caerian en el "resto"
# de la division, que es lo que haria salir unos elementos mas que otros.
function Get-SecureIndex([int]$max) {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = [byte[]]::new(4)
    $limit = [uint32]([Math]::Floor([uint32]::MaxValue / $max) * $max)
    while ($true) {
        $rng.GetBytes($bytes)
        $value = [BitConverter]::ToUInt32($bytes, 0)
        if ($value -lt $limit) { return [int]($value % $max) }
    }
}

$words = @(
    'abeja','abrigo','aceite','acero','agua','aguila','ahorro','ajedrez','alambre','albahaca',
    'alcohol','aldea','alfombra','algodon','almendra','ancla','anillo','antena','arana','arbol',
    'arcilla','arena','arroz','asfalto','avena','azucar','bahia','balcon','ballena','bambu',
    'banco','barco','bosque','botella','brisa','bronce','brujula','buzon','caballo','cabana',
    'cactus','cadena','cafe','caja','caldera','calle','camino','campana','canela','cangrejo',
    'canoa','cantera','capa','caramelo','carbon','cartera','cascada','castillo','cebolla','cedro',
    'cemento','ceniza','cepillo','cereza','cielo','ciervo','cima','ciruela','ciudad','clavo',
    'cobre','cocina','codigo','cohete','colina','columna','cometa','concha','conejo','coral',
    'corcho','cordel','corona','cristal','cuaderno','cuchara','cuerda','cueva','delfin','desierto',
    'diamante','disco','dragon','duna','eclipse','elefante','embudo','enigma','escalera','escoba',
    'espejo','espiga','esponja','estanque','estrella','fabrica','farol','fecha','ferrocarril','fiebre',
    'flauta','flecha','flor','fogata','fortuna','fosforo','fresa','frontera','fuente','fuego',
    'galaxia','galleta','ganso','garra','gaviota','gemelo','girasol','glaciar','globo','grano',
    'granja','grieta','grifo','guante','guitarra','gusano','hacha','harina','helecho','hielo',
    'hierro','higuera','hilo','hoja','hongo','horizonte','hormiga','horno','huerto','hueso',
    'humo','iglesia','imperio','imprenta','invierno','isla','jabon','jardin','jarra','jaula',
    'jazmin','jirafa','jungla','laberinto','ladrillo','lagarto','lago','lampara','lana','lanza',
    'lapiz','laurel','leche','lena','leon','libro','limon','linterna','llave','lluvia',
    'lobo','loma','loro','luna','madera','maiz','maleta','manzana','mapa','maquina',
    'marfil','mariposa','martillo','mascara','medalla','melon','membrillo','menta','mercado','miel',
    'molino','moneda','montana','morsa','mosaico','motor','muelle','muralla','musgo','naranja',
    'naipe','nave','neblina','nido','nieve','nogal','nube','nudo','nuez','oasis',
    'obrero','oceano','olivo','olla','onda','orquidea','oruga','oso','ostra','otono',
    'padrino','paisaje','pajaro','pala','palma','pantano','panuelo','papel','parque','pasillo',
    'pato','peine','pelicano','pendulo','perla','pescado','petalo','piano','picaporte','piedra',
    'pimienta','pincel','pino','pirata','piscina','pizarra','planeta','plata','playa','pluma',
    'polvo','pomelo','portal','pozo','pradera','puente','puerto','pulpo','quebrada','queso',
    'quinta','rabano','radio','raiz','rama','rapido','raton','rayo','reloj','remo',
    'resina','riachuelo','ribera','rincon','rio','roble','rocio','rueda','sabana','sal',
    'salmon','sandia','sardina','sauce','selva','semilla','sendero','serpiente','sierra','silla',
    'sirena','sombrero','soga','sol','sombra','sopa','tabla','taller','tambor','tapiz',
    'tejado','telar','tempano','tenedor','termita','tierra','tigre','tijera','tinta','toldo',
    'tomate','tormenta','tornillo','torre','tortuga','trigo','trineo','trompeta','tronco','trucha',
    'tulipan','tunel','uva','vainilla','valle','vapor','vela','veleta','ventana','verano',
    'vidrio','viento','vinagre','violin','volcan','yunque','zafiro','zanahoria','zapato','zorro'
)

Write-Host ""
Write-Host "==============================================================" -ForegroundColor Cyan
Write-Host "  Contrasenas generadas (elige UNA)" -ForegroundColor Cyan
Write-Host "==============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  FRASES  - mas faciles de escribir y recordar" -ForegroundColor Yellow
Write-Host ""
for ($i = 1; $i -le 3; $i++) {
    $phrase = (1..6 | ForEach-Object { $words[(Get-SecureIndex $words.Count)] }) -join '-'
    Write-Host "    $phrase" -ForegroundColor White
}

Write-Host ""
Write-Host "  ALEATORIAS - mas fuertes, para guardar en un gestor" -ForegroundColor Yellow
Write-Host ""
$alphabet = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'.ToCharArray()
for ($i = 1; $i -le 3; $i++) {
    $random = -join (1..24 | ForEach-Object { $alphabet[(Get-SecureIndex $alphabet.Count)] })
    Write-Host "    $random" -ForegroundColor White
}

Write-Host ""
Write-Host "  Las frases de 6 palabras tienen mas de 60 bits de entropia:"
Write-Host "  ni con todos los ordenadores del mundo se adivinan por fuerza bruta."
Write-Host ""
Write-Host "  IMPORTANTE:" -ForegroundColor Red
Write-Host "   - Elige una de estas, NO inventes una parecida (dejaria de ser aleatoria)."
Write-Host "   - Guardala en un gestor de contrasenas o en papel."
Write-Host "   - Cierra esta ventana cuando termines."
Write-Host ""
