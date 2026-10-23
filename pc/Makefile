CFLAGS ?= -O2 -g -Wall -Wextra
earshot-rx: earshot-rx.c
	$(CC) $(CFLAGS) $(shell pkg-config --cflags libpipewire-0.3 roc) -o $@ $< $(shell pkg-config --libs libpipewire-0.3 roc) -lm -lpthread
