import React from 'react';
import ReactDOM from 'react-dom/client';
import { createTheme, MantineProvider } from '@mantine/core';
import { DatesProvider } from '@mantine/dates';
import { Notifications } from '@mantine/notifications';
import App from './App';
import { ISRAEL_TIMEZONE } from './time';

// Mantine's styles (the only CSS in the app).
import '@mantine/core/styles.css';
import '@mantine/dates/styles.css';
import '@mantine/dropzone/styles.css';
import '@mantine/notifications/styles.css';

// A small theme on top of Mantine's defaults: one primary color, one radius.
const theme = createTheme({
  primaryColor: 'blue',
  defaultRadius: 'sm',
  components: {
    // Badges show tag names and replay states as written, not in capitals.
    Badge: { styles: { label: { textTransform: 'none' } } },
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <MantineProvider theme={theme} defaultColorScheme="dark">
      {/* Date pickers show and edit times in Israel time; their values are still real instants. */}
      <DatesProvider settings={{ timezone: ISRAEL_TIMEZONE, firstDayOfWeek: 0 }}>
        <Notifications position="top-right" />
        <App />
      </DatesProvider>
    </MantineProvider>
  </React.StrictMode>,
);
