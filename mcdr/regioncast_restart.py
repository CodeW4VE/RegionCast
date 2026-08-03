from mcdreforged.api.all import *

PLUGIN_METADATA = {
	'id': 'regioncast_restart',
	'version': '1.0.0',
	'name': 'RegionCast Restart',
	'description': 'Starts the server back up after RegionCast has copied its region files',
	'author': 'tvtvirus',
	'link': 'https://github.com/CodeW4VE/RegionCast'
}

# Printed by the mod on its way out, after the copy is done. See UpdateService.RESTART_MARKER.
MARKER = '[RegionCast] RESTART_REQUESTED'

# Set when we see the marker, cleared once we have acted on it. A plain module level flag is
# enough: MCDR only runs one server per instance.
restart_pending = False


def on_info(server: PluginServerInterface, info: Info):
	global restart_pending
	if info.is_user or info.content is None:
		return
	if MARKER in info.content:
		restart_pending = True
		# This has to happen here, not in on_server_stop. When the server process ends on its own
		# MCDR shuts itself down with it, and inside a container that takes everything with it:
		# "Server stopped / Stopping MCDR / bye" and the container exits. By the time on_server_stop
		# runs that decision is already made, so it has to be cancelled while the server is still
		# on its way down.
		server.set_exit_after_stop_flag(False)
		server.logger.info('RegionCast asked for a restart, holding MCDR open to start the server again')


def on_server_stop(server: PluginServerInterface, server_return_code: int):
	global restart_pending
	if not restart_pending:
		return
	restart_pending = False
	# The mod stopped the server itself and did its copying during shutdown, so by the time we
	# get here the region files are already in place and starting again is all that is left.
	server.logger.info('RegionCast: starting the server back up')
	server.start()
