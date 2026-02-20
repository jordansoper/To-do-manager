"""DataUpdateCoordinator for To-Do Manager."""
from __future__ import annotations

import logging
from datetime import timedelta

import aiohttp

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_URL
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

_LOGGER = logging.getLogger(__name__)

SCAN_INTERVAL = timedelta(seconds=30)


class TodoManagerCoordinator(DataUpdateCoordinator):
    """Fetch data from the To-Do Manager API."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        """Initialise the coordinator."""
        super().__init__(
            hass,
            _LOGGER,
            name="To-Do Manager",
            update_interval=SCAN_INTERVAL,
        )
        self.url = entry.data[CONF_URL]
        self.session = async_get_clientsession(hass)

    async def _async_update_data(self) -> dict:
        """Fetch all lists and their todos from the API."""
        try:
            async with self.session.get(
                f"{self.url}/api/lists",
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                resp.raise_for_status()
                lists = await resp.json()

            data: dict = {"lists": lists, "todos": {}}

            for lst in lists:
                async with self.session.get(
                    f"{self.url}/api/lists/{lst['id']}/todos",
                    timeout=aiohttp.ClientTimeout(total=10),
                ) as resp:
                    resp.raise_for_status()
                    data["todos"][lst["id"]] = await resp.json()

            return data

        except aiohttp.ClientError as err:
            raise UpdateFailed(
                f"Error communicating with To-Do Manager: {err}"
            ) from err
