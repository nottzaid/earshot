CFLAGS ?= -O2 -g -march=native -Wall -Wextra

all: earshot-rx aec-test
earshot-rx: earshot-rx.c aec.c aec.h
	$(CC) $(CFLAGS) $(shell pkg-config --cflags libpipewire-0.3 roc) -o $@ earshot-rx.c aec.c $(shell pkg-config --libs libpipewire-0.3 roc fftw3f) -lm -lpthread
aec-test: aec-test.c aec.c aec.h
	$(CC) $(CFLAGS) -o $@ aec-test.c aec.c $(shell pkg-config --libs fftw3f) -lm
