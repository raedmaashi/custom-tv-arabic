const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const valid = () => ({current:{temperature_2m:20,apparent_temperature:19,relative_humidity_2m:30,wind_speed_10m:10,weather_code:0,is_day:1,cloud_cover:0},hourly:{time:[],temperature_2m:[],weather_code:[],precipitation_probability:[],is_day:[]},daily:{time:['2026-10-22'],weather_code:[0],temperature_2m_max:[30],temperature_2m_min:[20],sunrise:['2026-10-22T06:00'],sunset:['2026-10-22T18:00'],precipitation_probability_max:[0]}});
function setup() {
  let raw = valid(); const data = {};
  const ctx=vm.createContext({window:{},localStorage:{getItem:key=>data[key]||null,setItem:(key,value)=>data[key]=value},setTimeout:()=>0,clearTimeout:()=>{},fetch:async()=>({ok:true,json:async()=>raw})});
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/site/js/weather.js'),'utf8'),ctx);
  return {api:ctx.window.TVWeather,data,set:value=>raw=value};
}
test('an invalid response preserves the last validated weather cache',async()=>{
  const s=setup();await s.api.fetch({latitude:28,longitude:36});const before=s.data['tvhome.weather.v1'];
  s.set({error:true});await assert.rejects(s.api.fetch({latitude:28,longitude:36}),/Incomplete/);
  assert.equal(s.data['tvhome.weather.v1'],before);assert.equal(s.api.cached().current.temp,20);
});
test('empty daily data is rejected before it can break the forecast screen',async()=>{
  const s=setup(),raw=valid();for(const key of Object.keys(raw.daily))raw.daily[key]=[];
  s.set(raw);await assert.rejects(s.api.fetch({latitude:28,longitude:36}),/Missing weather dates/);
  assert.equal(s.api.cached(),null);
});
test('incomplete numeric arrays never replace a valid cache',async()=>{
  const s=setup();await s.api.fetch({latitude:28,longitude:36});const raw=valid();raw.daily.temperature_2m_min=[];s.set(raw);
  await assert.rejects(s.api.fetch({latitude:28,longitude:36}),/Incomplete weather arrays/);assert.equal(s.api.cached().daily.length,1);
});
