-- Munchkin Monsters Database - Complete Spanish Edition
-- All monsters from Munchkin base game + expansions 2-9
-- Run this on the server: sqlite3 munchkin.db < monsters_seed.sql

-- Clear test monsters first
DELETE FROM monsters WHERE name LIKE '%test%' OR name LIKE '%Test%';

-- ============================================================================
-- MUNCHKIN 1 - JUEGO BASE (24 monstruos)
-- ============================================================================
INSERT OR REPLACE INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_at) VALUES
('m1-01', 'Planta en un Tiesto', 1, 0, 1, 1, 0, 'Pierde 1 nivel.', 'base', 1704153600000),
('m1-02', 'Babosa Babeante', 1, 0, 1, 1, 0, 'Pierdes tu armadura.', 'base', 1704153600000),
('m1-03', 'Rata Mortal', 1, 0, 1, 1, 0, 'Pierde 1 nivel.', 'base', 1704153600000),
('m1-04', 'Gusano Púrpura', 2, 0, 1, 1, 0, 'Pierdes el calzado.', 'base', 1704153600000),
('m1-05', 'Duende Cornudo', 2, 0, 1, 1, 0, 'Pierdes el objeto pequeño de mayor bonificación.', 'base', 1704153600000),
('m1-06', 'Murciélago Gigante', 3, 0, 1, 1, 0, 'Pierdes el casco. Si no tienes, pierde 1 nivel.', 'base', 1704153600000),
('m1-07', 'Ratas Muertas', 1, 0, 1, 1, 1, 'Pierde 2 niveles.', 'base', 1704153600000),
('m1-08', 'Esqueleto', 4, 0, 1, 1, 1, 'Pierdes tu objeto de clase más potente.', 'base', 1704153600000),
('m1-09', 'Orco con Máscara', 4, 0, 2, 1, 0, 'Pierde 2 niveles.', 'base', 1704153600000),
('m1-10', 'Pitufos Malignos', 4, 0, 2, 1, 0, 'Pierdes tu casco/sombrero. Sin casco, MUERE.', 'base', 1704153600000),
('m1-11', 'Lagartija Psicodélica', 5, 0, 2, 1, 0, 'Pierdes todas las cartas de tu mano.', 'base', 1704153600000),
('m1-12', 'Troll Chillón', 6, 0, 2, 1, 0, 'Pierde 2 niveles.', 'base', 1704153600000),
('m1-13', 'Arpia', 6, 0, 2, 1, 0, '+2 vs hombres. Pierdes 2 niveles.', 'base', 1704153600000),
('m1-14', 'Araña Lanuda', 8, 0, 2, 1, 0, 'Pierdes el calzado. Sin calzado, pierde 2 niveles.', 'base', 1704153600000),
('m1-15', 'Ogro', 8, 0, 2, 1, 0, 'Pierde 2 niveles.', 'base', 1704153600000),
('m1-16', 'Amazona', 8, 0, 2, 1, 0, '+5 vs hombres. Pierdes tu clase permanentemente.', 'base', 1704153600000),
('m1-17', 'Abogado', 10, 0, 2, 1, 0, 'Pierdes todos los tesoros que lleves.', 'base', 1704153600000),
('m1-18', 'Hippie Mutante', 10, 0, 3, 1, 0, 'Pierde 1 nivel y grita paz y amor.', 'base', 1704153600000),
('m1-19', 'Rey Tut', 12, 0, 3, 1, 1, '+4 vs Clérigo. MUERE.', 'base', 1704153600000),
('m1-20', 'Minotauro', 12, 0, 3, 1, 0, 'Pierde 3 niveles.', 'base', 1704153600000),
('m1-21', 'Gorgona', 14, 0, 4, 1, 0, 'Pierdes tu raza permanentemente.', 'base', 1704153600000),
('m1-22', 'Leprechaun', 14, 0, 3, 1, 0, 'Pierdes 2 niveles y tu objeto pequeño más valioso.', 'base', 1704153600000),
('m1-23', 'Dragoncito', 14, 0, 3, 1, 0, 'Pierde 2 niveles.', 'base', 1704153600000),
('m1-24', 'Nariz que Anda', 16, 0, 4, 1, 0, 'El siguiente jugador roba 2 cartas de Puerta.', 'base', 1704153600000),
('m1-25', 'Medusa', 18, 0, 4, 1, 0, '+4 vs Elfo. Pierdes todas las cartas de la mano Y 2 niveles.', 'base', 1704153600000),
('m1-26', 'Ninja Gigante', 18, 0, 4, 1, 0, '-3 vs Ladrón. MUERE (con objeto grande solo pierde 3 niveles).', 'base', 1704153600000),
('m1-27', 'Katrina', 20, 0, 5, 2, 0, 'Pierdes todos tus tesoros.', 'base', 1704153600000),
('m1-28', 'Dragón de Plutonio', 20, 0, 5, 2, 0, 'MUERE.', 'base', 1704153600000);

-- ============================================================================
-- MUNCHKIN 2 - HACHA DESCOMUNAL / PIFIAS CLERICALES
-- ============================================================================
INSERT OR REPLACE INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_at) VALUES
('m2-01', 'Ángel Caído', 6, 0, 2, 1, 0, '+4 vs Clérigo. Pierde 2 niveles.', 'hacha_descomunal', 1704153600000),
('m2-02', 'Demonio de la Pereza', 6, 0, 2, 1, 0, 'No puedes jugar cartas en tu próximo turno.', 'hacha_descomunal', 1704153600000),
('m2-03', 'Demonio de la Gula', 8, 0, 2, 1, 0, 'Pierdes todas las pociones.', 'hacha_descomunal', 1704153600000),
('m2-04', 'Demonio del Orgullo', 8, 0, 2, 1, 0, 'Pierdes tu objeto más valioso.', 'hacha_descomunal', 1704153600000),
('m2-05', 'Demonio de la Lujuria', 10, 0, 3, 1, 0, 'El jugador del sexo opuesto más cercano te roba 2 cartas.', 'hacha_descomunal', 1704153600000),
('m2-06', 'Demonio de la Ira', 12, 0, 3, 1, 0, 'MUERE si tienes menos de 5 cartas en mano.', 'hacha_descomunal', 1704153600000),
('m2-07', 'Demonio de la Envidia', 14, 0, 4, 1, 0, 'El jugador de menor nivel te roba 3 cartas.', 'hacha_descomunal', 1704153600000),
('m2-08', 'Demonio de la Avaricia', 16, 0, 4, 1, 0, 'Pierdes todo el oro y tesoros de menos de 300 monedas.', 'hacha_descomunal', 1704153600000),
('m2-09', 'Orco Ocelote', 4, 0, 2, 1, 0, 'Pierde 1 nivel.', 'hacha_descomunal', 1704153600000),
('m2-10', 'Gran Diablo', 18, 0, 5, 2, 0, '+6 vs Clérigo. MUERE.', 'hacha_descomunal', 1704153600000);

-- ============================================================================
-- MUNCHKIN 3 - PIFIAS CLERICALES / QUE LOCURA DE MONTURA
-- ============================================================================
INSERT OR REPLACE INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_at) VALUES
('m3-01', 'Caballo del Apocalipsis', 10, 0, 3, 1, 1, '+3 si tienes montura. Pierdes tu montura.', 'pifias_clericales', 1704153600000),
('m3-02', 'Burro Psicótico', 4, 0, 2, 1, 0, 'Pierde 1 nivel por cada montura que tengas.', 'pifias_clericales', 1704153600000),
('m3-03', 'Jinete Negro', 12, 0, 3, 1, 1, '+5 si no tienes montura. MUERE si no tienes montura.', 'pifias_clericales', 1704153600000),
('m3-04', 'Pegaso Rabioso', 8, 0, 2, 1, 0, 'Pierdes el calzado y 1 nivel.', 'pifias_clericales', 1704153600000),
('m3-05', 'Unicornio Maldito', 6, 0, 2, 1, 0, '+3 vs mujeres. Pierdes 2 niveles.', 'pifias_clericales', 1704153600000),
('m3-06', 'Centauro Salvaje', 8, 0, 2, 1, 0, 'Pierdes tu arma más potente.', 'pifias_clericales', 1704153600000),
('m3-07', 'Dragón de Carrusel', 14, 0, 4, 1, 0, 'Pierdes tu montura. Sin montura, pierde 3 niveles.', 'pifias_clericales', 1704153600000),
('m3-08', 'Monstruo de Feria', 10, 0, 3, 1, 0, 'Pierdes 2 niveles y un objeto grande.', 'pifias_clericales', 1704153600000);

-- ============================================================================
-- MUNCHKIN 4 - ¡ANDA QUE OJO! / ¡ANDA QUE BICHO!
-- ============================================================================
INSERT OR REPLACE INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_at) VALUES
('m4-01', 'Ojo Flotante', 2, 0, 1, 1, 0, 'Pierde 1 nivel.', 'anda_que_ojo', 1704153600000),
('m4-02', 'Ojo Malvado', 6, 0, 2, 1, 0, 'Pierdes todos los objetos de la cabeza.', 'anda_que_ojo', 1704153600000),
('m4-03', 'Gran Ojo', 10, 0, 3, 1, 0, 'Pierde 2 niveles, ciego 1 turno.', 'anda_que_ojo', 1704153600000),
('m4-04', 'Ojo de Sauron', 16, 0, 4, 1, 0, '+5 vs Enanos. MUERE si eres Enano.', 'anda_que_ojo', 1704153600000),
('m4-05', 'Gusano Ocular', 4, 0, 2, 1, 0, 'Pierdes una carta al azar de tu mano.', 'anda_que_ojo', 1704153600000),
('m4-06', 'Bicho Oculista', 8, 0, 2, 1, 0, 'Pierdes 2 objetos pequeños.', 'anda_que_ojo', 1704153600000),
('m4-07', 'Óptico Asesino', 12, 0, 3, 1, 0, 'Pierdes todas las armas.', 'anda_que_ojo', 1704153600000),
('m4-08', 'Iris Gigante', 14, 0, 4, 1, 0, 'Pierde 3 niveles.', 'anda_que_ojo', 1704153600000);

-- ============================================================================
-- MUNCHKIN 5 - EXPLORADORES FLIPADOS
-- ============================================================================
INSERT OR REPLACE INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_at) VALUES
('m5-01', 'Ranger Zombi', 4, 0, 2, 1, 1, '+3 vs Ranger. Pierde 2 niveles.', 'exploradores', 1704153600000),
('m5-02', 'Árbol Furioso', 8, 0, 2, 1, 0, 'Pierdes todos los objetos de madera.', 'exploradores', 1704153600000),
('m5-03', 'Oso Mutante', 10, 0, 3, 1, 0, 'Pierde 3 niveles.', 'exploradores', 1704153600000),
('m5-04', 'Lobo Hambriento', 6, 0, 2, 1, 0, 'Pierdes tu armadura.', 'exploradores', 1704153600000),
('m5-05', 'Serpiente Venenosa', 2, 0, 1, 1, 0, 'Pierde 1 nivel.', 'exploradores', 1704153600000),
('m5-06', 'Jabalí Salvaje', 5, 0, 2, 1, 0, 'Pierdes 2 niveles.', 'exploradores', 1704153600000),
('m5-07', 'Puma Acechador', 7, 0, 2, 1, 0, 'Pierdes el calzado.', 'exploradores', 1704153600000),
('m5-08', 'Águila Gigante', 9, 0, 3, 1, 0, 'Pierdes el casco y 1 nivel.', 'exploradores', 1704153600000),
('m5-09', 'Alce Rabioso', 11, 0, 3, 1, 0, 'Pierde 2 niveles y huye a -2.', 'exploradores', 1704153600000),
('m5-10', 'Oso Grizzly', 14, 0, 4, 1, 0, 'MUERE si no tienes armadura.', 'exploradores', 1704153600000);

-- ============================================================================
-- MUNCHKIN 6 - MAZMORRAS OMINOSAS
-- ============================================================================
INSERT OR REPLACE INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_at) VALUES
('m6-01', 'Golem de Lava', 12, 0, 3, 1, 0, 'Destruye un objeto. Pierde 2 niveles.', 'mazmorras', 1704153600000),
('m6-02', 'Mimic', 8, 0, 2, 1, 0, 'Pierdes el objeto que más uses.', 'mazmorras', 1704153600000),
('m6-03', 'Fantasma de la Mazmorra', 6, 0, 2, 1, 1, 'Pierde 1 nivel, asustado 1 turno.', 'mazmorras', 1704153600000),
('m6-04', 'Slime Ácido', 4, 0, 2, 1, 0, 'Destruye tu armadura.', 'mazmorras', 1704153600000),
('m6-05', 'Cubo Gelatinoso', 6, 0, 2, 1, 0, 'Pierdes un objeto al azar.', 'mazmorras', 1704153600000),
('m6-06', 'Elemental de Tierra', 10, 0, 3, 1, 0, 'Pierde 2 niveles.', 'mazmorras', 1704153600000),
('m6-07', 'Gárgola', 8, 0, 2, 1, 0, 'Pierdes todos los objetos de piedra.', 'mazmorras', 1704153600000),
('m6-08', 'Espectro', 10, 0, 3, 1, 1, '+4 vs Clérigo. Pierde 2 niveles.', 'mazmorras', 1704153600000),
('m6-09', 'Licántropo', 12, 0, 3, 1, 0, 'Pierde 2 niveles y una maldición aleatoria.', 'mazmorras', 1704153600000),
('m6-10', 'Vampiro', 14, 0, 4, 1, 1, '+4 vs Clérigo. Pierde 3 niveles.', 'mazmorras', 1704153600000),
('m6-11', 'Momia', 10, 0, 3, 1, 1, 'Pierdes todos los objetos de tela.', 'mazmorras', 1704153600000),
('m6-12', 'Devorador', 16, 0, 4, 1, 0, 'MUERE.', 'mazmorras', 1704153600000);

-- ============================================================================
-- MUNCHKIN 7 - TRAMPAS A DOS MANOS
-- ============================================================================
INSERT OR REPLACE INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_at) VALUES
('m7-01', 'Trampa Móvil', 6, 0, 2, 1, 0, 'Pierde 2 niveles.', 'trampas', 1704153600000),
('m7-02', 'Cofre Explosivo', 4, 0, 1, 1, 0, 'Pierdes todos los tesoros de la mano.', 'trampas', 1704153600000),
('m7-03', 'Puerta Falsa', 8, 0, 2, 1, 0, 'Vuelves a empezar la mazmorra.', 'trampas', 1704153600000),
('m7-04', 'Suelo Trampa', 10, 0, 3, 1, 0, 'Pierdes el calzado y 2 niveles.', 'trampas', 1704153600000),
('m7-05', 'Estatua Animada', 8, 0, 2, 1, 0, 'Pierde 2 niveles.', 'trampas', 1704153600000),
('m7-06', 'Dardo Envenenado', 2, 0, 1, 1, 0, 'Pierde 1 nivel.', 'trampas', 1704153600000),
('m7-07', 'Hacha Oscilante', 6, 0, 2, 1, 0, 'Pierdes la armadura.', 'trampas', 1704153600000),
('m7-08', 'Foso de Pinchos', 12, 0, 3, 1, 0, 'MUERE si no tienes calzado.', 'trampas', 1704153600000);

-- ============================================================================
-- MUNCHKIN 8 - CENTAUROS Y ARREPENTIDOS / MEZCLA A LO LOCO
-- ============================================================================
INSERT OR REPLACE INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_at) VALUES
('m8-01', 'Centauro Guerrero', 10, 0, 3, 1, 0, 'Pierde 2 niveles.', 'centauros', 1704153600000),
('m8-02', 'Centauro Arquero', 8, 0, 2, 1, 0, 'Pierdes el arco o arma a distancia.', 'centauros', 1704153600000),
('m8-03', 'Arrepentido', 6, 0, 2, 1, 1, 'Reza 3 turnos (no puedes atacar).', 'centauros', 1704153600000),
('m8-04', 'Centauro Líder', 14, 0, 4, 1, 0, '+2 por cada centauro en juego. Pierde 3 niveles.', 'centauros', 1704153600000),
('m8-05', 'Híbrido Horrible', 10, 0, 3, 1, 0, 'Pierdes tu raza temporalmente.', 'centauros', 1704153600000),
('m8-06', 'Quimera', 12, 0, 3, 1, 0, 'Pierde 2 niveles y un objeto grande.', 'centauros', 1704153600000),
('m8-07', 'Mantícora', 14, 0, 4, 1, 0, 'Pierde 3 niveles.', 'centauros', 1704153600000),
('m8-08', 'Grifo', 10, 0, 3, 1, 0, 'Pierdes el casco y 1 nivel.', 'centauros', 1704153600000);

-- ============================================================================
-- MUNCHKIN 9 - JURÁSICO ESPÁSTICO
-- ============================================================================
INSERT OR REPLACE INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_at) VALUES
('m9-01', 'Velociraptor', 6, 0, 2, 1, 0, 'Pierde 2 niveles. Huye a -2.', 'jurasico', 1704153600000),
('m9-02', 'T-Rex', 18, 0, 5, 2, 0, 'MUERE.', 'jurasico', 1704153600000),
('m9-03', 'Pterodáctilo', 8, 0, 2, 1, 0, 'Pierdes el casco o sombrero.', 'jurasico', 1704153600000),
('m9-04', 'Triceratops', 12, 0, 3, 1, 0, 'Pierdes la armadura y 1 nivel.', 'jurasico', 1704153600000),
('m9-05', 'Estegosaurio', 10, 0, 3, 1, 0, 'Pierde 2 niveles.', 'jurasico', 1704153600000),
('m9-06', 'Braquiosaurio', 14, 0, 4, 1, 0, 'Te aplasta. Pierde 3 niveles.', 'jurasico', 1704153600000),
('m9-07', 'Dilofosaurio', 8, 0, 2, 1, 0, 'Pierdes la vista. Descarta todas las cartas de tu mano.', 'jurasico', 1704153600000),
('m9-08', 'Espinosaurio', 16, 0, 4, 1, 0, 'Pierde 4 niveles.', 'jurasico', 1704153600000),
('m9-09', 'Anquilosaurio', 10, 0, 3, 1, 0, 'Pierdes el arma más potente.', 'jurasico', 1704153600000),
('m9-10', 'Carnotauro', 12, 0, 3, 1, 0, 'Pierde 2 niveles y huye a -3.', 'jurasico', 1704153600000),
('m9-11', 'Pack de Compys', 4, 0, 2, 1, 0, '+1 por cada monstruo ya en combate. Pierde 1 nivel.', 'jurasico', 1704153600000),
('m9-12', 'Plesiosaurio', 14, 0, 4, 1, 0, 'Pierde 3 niveles.', 'jurasico', 1704153600000);

-- ============================================================================
-- MONSTRUOS ADICIONALES CLÁSICOS / EXTRAS
-- ============================================================================
INSERT OR REPLACE INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_at) VALUES
('mx-01', 'Liche', 16, 0, 4, 2, 1, '+4 vs Clérigo. MUERE.', 'extra', 1704153600000),
('mx-02', 'Hidra', 14, 0, 4, 1, 0, 'Regenera +1 cada turno. Pierde 3 niveles.', 'extra', 1704153600000),
('mx-03', 'Behemoth', 18, 0, 5, 2, 0, 'Pierde 4 niveles.', 'extra', 1704153600000),
('mx-04', 'Basilisco', 12, 0, 3, 1, 0, 'Queda petrificado. Pierde el turno.', 'extra', 1704153600000),
('mx-05', 'Demogorgon', 20, 0, 5, 2, 0, 'MUERE y pierdes un nivel permanentemente.', 'extra', 1704153600000),
('mx-06', 'Cerbero', 14, 0, 4, 1, 0, 'Pierde 3 niveles.', 'extra', 1704153600000),
('mx-07', 'Hombre Lobo', 10, 0, 3, 1, 0, 'Pierde 2 niveles.', 'extra', 1704153600000),
('mx-08', 'Bruja Malvada', 8, 0, 2, 1, 0, '+3 vs mujeres. Pierdes una carta de maldición.', 'extra', 1704153600000),
('mx-09', 'Goblin Rey', 6, 0, 2, 1, 0, '+2 por cada goblin en juego. Pierde 1 nivel.', 'extra', 1704153600000),
('mx-10', 'Kobold', 2, 0, 1, 1, 0, 'Pierde 1 nivel.', 'extra', 1704153600000),
('mx-11', 'Hobgoblin', 4, 0, 2, 1, 0, 'Pierdes un objeto pequeño.', 'extra', 1704153600000),
('mx-12', 'Gnoll', 6, 0, 2, 1, 0, 'Pierde 2 niveles.', 'extra', 1704153600000),
('mx-13', 'Bugbear', 8, 0, 2, 1, 0, 'Pierdes la armadura.', 'extra', 1704153600000),
('mx-14', 'Duende Oscuro', 4, 0, 2, 1, 0, 'Pierde 1 nivel.', 'extra', 1704153600000),
('mx-15', 'Troll de las Cavernas', 10, 0, 3, 1, 0, 'Regenera si no usas fuego. Pierde 2 niveles.', 'extra', 1704153600000),
('mx-16', 'Gigante de Hielo', 14, 0, 4, 1, 0, 'Congela un objeto. Pierde 3 niveles.', 'extra', 1704153600000),
('mx-17', 'Gigante de Fuego', 14, 0, 4, 1, 0, 'Quema un objeto. Pierde 3 niveles.', 'extra', 1704153600000),
('mx-18', 'Elemental de Aire', 10, 0, 3, 1, 0, 'Te lanza lejos. Pierde 2 niveles.', 'extra', 1704153600000),
('mx-19', 'Elemental de Agua', 10, 0, 3, 1, 0, 'Te ahoga parcialmente. Pierde 2 niveles.', 'extra', 1704153600000),
('mx-20', 'Elemental de Fuego', 12, 0, 3, 1, 0, 'Quema tu armadura.', 'extra', 1704153600000),
('mx-21', 'Demonio Menor', 8, 0, 2, 1, 0, 'Pierde 2 niveles.', 'extra', 1704153600000),
('mx-22', 'Diablo Mayor', 16, 0, 4, 1, 0, '+4 vs Clérigo. Pierde 3 niveles.', 'extra', 1704153600000),
('mx-23', 'Súcubo', 10, 0, 3, 1, 0, '+5 vs hombres. Pierde 2 niveles.', 'extra', 1704153600000),
('mx-24', 'Íncubo', 10, 0, 3, 1, 0, '+5 vs mujeres. Pierde 2 niveles.', 'extra', 1704153600000),
('mx-25', 'Zombi', 4, 0, 1, 1, 1, 'Pierde 1 nivel.', 'extra', 1704153600000),
('mx-26', 'Espíritu Maligno', 8, 0, 2, 1, 1, '+3 vs Clérigo. Pierde 2 niveles.', 'extra', 1704153600000),
('mx-27', 'Aparición', 6, 0, 2, 1, 1, 'Pierde 1 nivel y una carta al azar.', 'extra', 1704153600000),
('mx-28', 'Wraith', 12, 0, 3, 1, 1, 'Pierde 2 niveles y el arma.', 'extra', 1704153600000),
('mx-29', 'Dullahan', 14, 0, 4, 1, 1, 'MUERE si no tienes casco.', 'extra', 1704153600000),
('mx-30', 'Banshee', 10, 0, 3, 1, 1, 'Grito mortal. Pierde 2 niveles.', 'extra', 1704153600000);

-- ============================================================================
-- Verify count
-- ============================================================================
SELECT expansion, COUNT(*) as cantidad FROM monsters GROUP BY expansion ORDER BY expansion;
SELECT 'Total monstruos:' as info, COUNT(*) as total FROM monsters;
