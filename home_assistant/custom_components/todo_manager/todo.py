"""Todo platform for To-Do Manager."""
from __future__ import annotations

import aiohttp

from homeassistant.components.todo import (
    TodoItem,
    TodoItemStatus,
    TodoListEntity,
    TodoListEntityFeature,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_URL
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from . import DOMAIN
from .coordinator import TodoManagerCoordinator


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up To-Do Manager todo entities from a config entry."""
    coordinator: TodoManagerCoordinator = hass.data[DOMAIN][entry.entry_id]
    async_add_entities(
        TodoManagerListEntity(coordinator, lst, entry)
        for lst in coordinator.data["lists"]
    )


class TodoManagerListEntity(CoordinatorEntity, TodoListEntity):
    """A To-Do Manager list exposed as a Home Assistant TodoListEntity."""

    _attr_has_entity_name = True
    _attr_supported_features = (
        TodoListEntityFeature.CREATE_TODO_ITEM
        | TodoListEntityFeature.UPDATE_TODO_ITEM
        | TodoListEntityFeature.DELETE_TODO_ITEM
    )

    def __init__(
        self,
        coordinator: TodoManagerCoordinator,
        lst: dict,
        entry: ConfigEntry,
    ) -> None:
        """Initialise the entity."""
        super().__init__(coordinator)
        self._list_id: int = lst["id"]
        self._url: str = entry.data[CONF_URL]
        self._attr_name = lst["name"]
        self._attr_unique_id = f"{DOMAIN}_{entry.entry_id}_list_{lst['id']}"

    @property
    def todo_items(self) -> list[TodoItem]:
        """Return the current todo items for this list."""
        todos = self.coordinator.data["todos"].get(self._list_id, [])
        return [
            TodoItem(
                uid=str(t["id"]),
                summary=t["title"],
                status=(
                    TodoItemStatus.COMPLETE
                    if t["completed"]
                    else TodoItemStatus.NEEDS_ACTION
                ),
                due=t.get("due_date") or None,
                description=t.get("description") or None,
            )
            for t in todos
        ]

    async def async_create_todo_item(self, item: TodoItem) -> None:
        """Create a new todo item via the API."""
        async with self.coordinator.session.post(
            f"{self._url}/api/todos",
            json={
                "title": item.summary,
                "description": item.description or "",
                "list_id": self._list_id,
                "due_date": item.due.isoformat() if item.due else None,
            },
            timeout=aiohttp.ClientTimeout(total=10),
        ) as resp:
            resp.raise_for_status()
        await self.coordinator.async_request_refresh()

    async def async_update_todo_item(self, item: TodoItem) -> None:
        """Toggle completion when a todo's status is changed."""
        todos = self.coordinator.data["todos"].get(self._list_id, [])
        current = next((t for t in todos if str(t["id"]) == item.uid), None)
        if not current:
            return

        currently_completed = current["completed"]
        wants_complete = item.status == TodoItemStatus.COMPLETE

        if currently_completed != wants_complete:
            async with self.coordinator.session.post(
                f"{self._url}/api/todos/{item.uid}/toggle",
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                resp.raise_for_status()
            await self.coordinator.async_request_refresh()

    async def async_delete_todo_items(self, uids: list[str]) -> None:
        """Delete one or more todo items via the API."""
        for uid in uids:
            async with self.coordinator.session.delete(
                f"{self._url}/api/todos/{uid}",
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                resp.raise_for_status()
        await self.coordinator.async_request_refresh()
