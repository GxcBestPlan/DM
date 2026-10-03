import { createApp } from 'vue'
import './ui.css'
import App from './App.vue'
import router from './router'
import { restore } from './session'

restore().then(() => {
  createApp(App).use(router).mount('#app')
})
