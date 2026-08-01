import { initSender } from './sender.js';
import { initReceiver } from './receiver.js';

document.addEventListener('DOMContentLoaded', () => {
  const tabBtns = document.querySelectorAll('.tab-btn');
  const sections = document.querySelectorAll('.mode-section');

  tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      // Remove active from all
      tabBtns.forEach(b => b.classList.remove('active'));
      sections.forEach(s => s.classList.remove('active', 'hidden'));
      sections.forEach(s => s.classList.add('hidden'));

      // Set active
      btn.classList.add('active');
      const targetId = btn.getAttribute('data-target');
      const targetSection = document.getElementById(targetId);
      targetSection.classList.remove('hidden');
      targetSection.classList.add('active');
    });
  });

  initSender();
  initReceiver();
});
