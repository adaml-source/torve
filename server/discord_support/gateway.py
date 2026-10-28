"""Message-only gateway adapter; HTTP interactions remain in the same ASGI process."""
import asyncio
import logging

from .conversation import Event


def make_gateway(cfg,conversations,record):
    import discord
    # SDK exceptions can include packets. Controlled application events replace
    # those logs; no gateway message objects should reach an exception collector.
    for name in ("discord","discord.client","discord.gateway","discord.http","discord.state"):
        logging.getLogger(name).disabled = True

    intents = discord.Intents.none()
    intents.guilds = True
    intents.guild_messages = True
    intents.message_content = True

    class Gateway(discord.Client):
        async def on_ready(self):
            # A dedicated support application is required, never an admin bot.
            guild = self.get_guild(int(cfg.guild_id))
            member = guild.me if guild else None
            forbidden = 8 | 16 | 32 | (1<<28) | (1<<29)
            if str(self.user.id)!=cfg.application_id or not member or member.guild_permissions.value & forbidden:
                record("gateway_permission_rejected")
                await self.close()
                return
            if cfg.tagging_enabled and any(not guild.get_channel(int(f)) or not guild.get_channel(int(f)).permissions_for(member).manage_threads for f in cfg.forum_ids):
                record("gateway_tag_permission_rejected")
                await self.close()
                return
            self.support_ready = True
            record("gateway_ready")

        async def on_disconnect(self):
            self.support_ready = False
            record("gateway_disconnected")

        async def on_resumed(self):
            self.support_ready = True
            record("gateway_resumed")

        async def on_message(self,message):
            if not getattr(self,"support_ready",False) or not message.guild or str(message.guild.id)!=cfg.guild_id:
                return
            channel = message.channel
            if str(channel.id) not in cfg.channels and not (isinstance(channel,discord.Thread) and str(channel.parent_id) in cfg.forum_ids):
                return
            if message.author.bot or message.webhook_id or message.type not in (discord.MessageType.default,discord.MessageType.reply):
                return
            # Reject backlog floods rather than creating unbounded tasks/requests.
            if self.receiving.locked():
                record("gateway_backpressure")
                return
            async with self.receiving:
                try:
                    await conversations.receive(Event(str(message.id),str(message.guild.id),str(channel.id),str(message.author.id),message.content,
                                                      attachments=bool(message.attachments)))
                except Exception:
                    record("gateway_message_failed")

        async def on_error(self,event_method,*args,**kwargs):
            # discord.py's default traceback includes message objects; suppress it.
            record("gateway_event_failed")

    result = Gateway(intents=intents,member_cache_flags=discord.MemberCacheFlags.none(),max_messages=None)
    result.support_ready = False
    result.receiving = asyncio.Semaphore(4)
    return result
