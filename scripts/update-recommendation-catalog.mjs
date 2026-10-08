// Reuse the frontend's generated game data; this command never fetches the network.
// node scripts/update-recommendation-catalog.mjs ../zoot-plus-frontend/src/models/generated/operators.json
import { readFile, writeFile } from 'node:fs/promises'

const source = process.argv[2]
if (!source) throw new Error('Pass the path to the generated frontend operators.json')
const { OPERATORS } = JSON.parse(await readFile(source, 'utf8'))
const roles = { PIONEER: 'Pioneer', WARRIOR: 'Warrior', TANK: 'Tank', SNIPER: 'Sniper', CASTER: 'Caster', MEDIC: 'Medic', SUPPORT: 'Support', SPECIAL: 'Special' }
const modules = { '': 0, X: 1, Y: 2, A: 3, D: 4 }
const catalog = OPERATORS.filter((operator) => roles[operator.prof] && operator.rarity > 0).map((operator) => ({
  id: operator.id,
  name: operator.name,
  role: roles[operator.prof],
  rarity: operator.rarity,
  modules: (operator.modules ?? []).map((module) => modules[module] ?? null),
}))
await writeFile(new URL('../src/main/resources/recommendation-operators.json', import.meta.url), `${JSON.stringify(catalog)}\n`)
console.info(`Generated ${catalog.length} operator identities`)
